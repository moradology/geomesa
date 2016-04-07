package org.locationtech.geomesa.accumulo.index

import com.google.common.primitives.{Bytes, Longs}
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.{Geometry, GeometryCollection}
import org.apache.hadoop.io.Text
import org.geotools.factory.Hints
import org.locationtech.geomesa.accumulo.data.tables.Z2Table
import org.locationtech.geomesa.accumulo.iterators._
import org.locationtech.geomesa.curve.{NormalizedLat, NormalizedLon}
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.Filter
import org.opengis.filter.spatial._

/**
  * Created by afox on 4/7/16.
  */
class Z2IdxStrategy(val filter: QueryFilter) extends Strategy with LazyLogging with IndexFilterHelpers {

  import org.locationtech.geomesa.filter._
  import FilterHelper._
  import QueryHints._
  import Z2IdxStrategy._
  import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType._

  val normLat = NormalizedLat(32)
  val normLon = NormalizedLon(32)
  /**
    * Plans the query - strategy implementations need to define this
    */
  override def getQueryPlan(queryPlanner: QueryPlanner, hints: Hints, output: ExplainerOutputType): QueryPlan = {
    val sft = queryPlanner.sft
    val acc = queryPlanner.acc

    val (geomFilters, temporalFilters) = {
      val (g, t) = filter.primary.partition(isSpatialFilter)
      if (g.isEmpty) {
        // allow for date only queries - if no geom, use whole world
        (Seq(ff.bbox(sft.getGeomField, -180, -90, 180, 90, "EPSG:4326")), t)
      } else {
        (g, t)
      }
    }

    output(s"Geometry filters: ${filtersToString(geomFilters)}")
    output(s"Temporal filters: ${filtersToString(temporalFilters)}")

    // standardize the two key query arguments:  polygon and date-range
    val geomsToCover = tryReduceGeometryFilter(geomFilters).flatMap(decomposeToGeometry)

    val collectionToCover: Geometry = geomsToCover match {
      case Nil => null
      case seq: Seq[Geometry] => new GeometryCollection(geomsToCover.toArray, geomsToCover.head.getFactory)
    }

    val geometryToCover = netGeom(collectionToCover)

    output(s"GeomsToCover: $geometryToCover")

    val fp = FILTERING_ITER_PRIORITY

    val ecql: Option[Filter] = if (sft.isPoints) {
      // for normal bboxes, the index is fine enough that we don't need to apply the filter on top of it
      // this may cause some minor errors at extremely fine resolution, but the performance is worth it
      // TODO GEOMESA-1000 add some kind of 'loose bbox' config, a la postgis
      // if we have a complicated geometry predicate, we need to pass it through to be evaluated
      val complexGeomFilter = filterListAsAnd(geomFilters).filter(isComplicatedSpatialFilter)
      (complexGeomFilter, filter.secondary) match {
        case (Some(gf), Some(fs)) => filterListAsAnd(Seq(gf, fs))
        case (None, fs)           => fs
        case (gf, None)           => gf
      }
    } else {
      // for non-point geoms, the index is coarse-grained, so we always apply the full filter
      filter.filter
    }

    val (iterators, kvsToFeatures, colFamily, hasDupes) = {
      val transforms = for {
        tdef <- hints.getTransformDefinition
        tsft <- hints.getTransformSchema
      } yield { (tdef, tsft) }
      output(s"Transforms: $transforms")

      val iters = (ecql, transforms) match {
        case (None, None) => Seq.empty
        case _ => Seq(KryoLazyFilterTransformIterator.configure(sft, ecql, transforms, fp))
      }
      (iters, queryPlanner.defaultKVsToFeatures(hints), Z2Table.FULL_CF, sft.nonPoints)
    }

    val z2table = acc.getTableName(sft.getTypeName, Z2Table)
    val numThreads = acc.getSuggestedThreads(sft.getTypeName, Z2Table)

    // setup Z2 iterator
    val env = geometryToCover.getEnvelopeInternal
    val (lx, ly, ux, uy) = (env.getMinX, env.getMinY, env.getMaxX, env.getMaxY)

    val getRanges: (Seq[Array[Byte]], (Double, Double), (Double, Double)) => Seq[org.apache.accumulo.core.data.Range] = getPointRanges

    val prefixes = Z2Table.SPLIT_ARRAYS

    val zIter = Z2Iterator.configure(sft.isPoints,
      normLon.normalize(lx), normLon.normalize(ux),
      normLat.normalize(ly), normLon.normalize(uy), splits = true, Z2IdxStrategy.Z2_ITER_PRIORITY)

    val iters = Seq(zIter) ++ iterators
    BatchScanPlan(z2table, getRanges(prefixes, (lx, ux), (ly, uy)), iters, Seq(colFamily), kvsToFeatures, numThreads, hasDupes)
  }

  def getPointRanges(prefixes: Seq[Array[Byte]], x: (Double, Double), y: (Double, Double)): Seq[org.apache.accumulo.core.data.Range] = {
    val (xmin, xmax) = x
    val (ymin, ymax) = y
    Z2Table.ZCURVE2D.toRanges(xmin, xmax, ymin, ymax).flatMap { case indexRange =>
      val startBytes = Longs.toByteArray(indexRange.lower)
      val endBytes = Longs.toByteArray(indexRange.upper)
      prefixes.map { prefix =>
        val start = new Text(Bytes.concat(prefix, startBytes))
        val end   = org.apache.accumulo.core.data.Range.followingPrefix(new Text(Bytes.concat(prefix, endBytes)))
        new org.apache.accumulo.core.data.Range(start, true, end, false)
      }
    }
  }

}

object Z2IdxStrategy extends StrategyProvider {

  val Z2_ITER_PRIORITY = 21
  val FILTERING_ITER_PRIORITY = 25

  /**
    * Gets the estimated cost of running the query. Currently, cost is hard-coded to sort between
    * strategies the way we want. Z2 should be more than id lookups (at 1), high-cardinality attributes (at 1)
    * and less than STidx (at 400) and unknown cardinality attributes (at 999).
    *
    * Eventually cost will be computed based on dynamic metadata and the query.
    */
  override def getCost(filter: QueryFilter, sft: SimpleFeatureType, hints: StrategyHints) =
    if (filter.primary.length > 1) 200 else 400

  def isComplicatedSpatialFilter(f: Filter): Boolean = {
    f match {
      case _: BBOX => false
      case _: DWithin => true
      case _: Contains => true
      case _: Crosses => true
      case _: Intersects => true
      case _: Overlaps => true
      case _: Within => true
      case _ => false        // Beyond, Disjoint, DWithin, Equals, Touches
    }
  }

}

