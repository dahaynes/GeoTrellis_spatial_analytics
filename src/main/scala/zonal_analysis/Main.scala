package zonal_analysis

import geotrellis.raster._
import geotrellis.spark._
import geotrellis.vector.io._
import geotrellis.vector.io.json._

import scala.io.StdIn.{readInt, readLine}
import geotrellis.raster.io.geotiff.reader.GeoTiffReader
import geotrellis.raster.io.geotiff._

import scala.io.Source
import geotrellis.vector._
import geotrellis.vector.io._
import spray.json._
import spray.json.DefaultJsonProtocol._
import geotrellis.raster._
import datasets.rasterdatasets.myRaster
import datasets.vectordatasets.myVector
//Has TileLayout Object, MultibandTile
import geotrellis.raster.io.geotiff._
import geotrellis.raster.render._
import geotrellis.raster.resample._
import geotrellis.raster.reproject._
import geotrellis.raster.summary.polygonal._
import geotrellis.raster.rasterize._
import geotrellis.raster.rasterize.polygon._
import geotrellis.raster.mapalgebra.local._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.file._
import geotrellis.spark.io.hadoop._
import geotrellis.spark.io.index._
import geotrellis.spark.pyramid._
import geotrellis.spark.reproject._
import geotrellis.spark.tiling._
import geotrellis.spark.render._
//Vector Json
import geotrellis.vector._
import geotrellis.vector.io._
import geotrellis.vector.io.json._
//ProjectedExtent object
import org.apache.spark._
import org.apache.spark.rdd._
import org.apache.spark.HashPartitioner
import org.apache.spark.rdd.RDD
//Libraries for reading a json
import spray.json._
import spray.json.DefaultJsonProtocol._
//File Object
import scala.io.StdIn
import java.io.File
import java.io._
import scala.collection.mutable.ListBuffer
import org.apache.log4j.{Level, Logger}


object Main {

  def main(args: Array[String]): Unit = {

    Logger.getLogger("org.apache.spark").setLevel(Level.ERROR)
    //Raster Dataset Path
    val rasterDatasets = List(
    //new myRaster("glc", "/home/david/Downloads/glc2000.tif", 16, 1, 4326),
    new myRaster("glc", "/data/projects/G-818404/glc2000_clipped.tif", 16, 1),
    new myRaster("meris", "/data/projects/G-818404/meris_2010_clipped.tif", 100, 1),
    //new myRaster("nlcd", "/home/david/Downloads/nlcd_2006.tif", 21, 1, 5070)
    new myRaster("nlcd", "/data/projects/G-818404/nlcd_2006.tif", 21, 1)
    //new rasterdataset("meris_3m", "/data/projects/G-818404/meris_2010_clipped_3m/", 100, 1)
    )

    val vectorDatasets = List(
     //new myVector("states", "/home/david/shapefiles", "states_2.geojson")
     new myVector("states", "/data/projects/G-818404/shapefiles", "states_2.geojson")

    )

    val tileSizes = Array(25, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000) //, 1500, 2000, 2500, 3000, 3500, 4000)

    val outSummaryStats = "/home/david/geotrellis_glc_stats_zonalstats.csv"
    //val writer = new BufferedWriter(new )
    val outCSVPath = "/data/projects/G-818404/geotrellis_zonalstatst_9_16_2018_12instances.csv" //
    val writer = new PrintWriter(new File(outCSVPath))
    writer.write("analytic,raster_dataset,tilesize,vector_dataset,total_time,multipolygon_time, polygon_time, run\n")

    val conf = new SparkConf().setMaster("local[12]").setAppName("Zonal Stats").set("spark.serializer", "org.apache.spark.serializer.KryoSerializer").set("spark.kryo.regisintrator", "geotrellis.spark.io.kryo.KryoRegistrator")
    implicit val sc = new SparkContext(conf)

    for(r<-rasterDatasets) {

      for (tilesize <- tileSizes) {
        //val tilesize = 250

        //val geoTiff: SinglebandGeoTiff = SinglebandGeoTiff(r.thePath)
        val rasterRDD: RDD[(ProjectedExtent, Tile)] = HadoopGeoTiffRDD.spatial(r.thePath, HadoopGeoTiffRDD.Options.DEFAULT)
        val (_, rasterMetaData) = TileLayerMetadata.fromRdd(rasterRDD, FloatingLayoutScheme(tilesize))
        val tiledRaster: RDD[(SpatialKey, geotrellis.raster.Tile)] = rasterRDD.tileToLayout(rasterMetaData.cellType, rasterMetaData.layout)
        val rasterTileLayerRDD: TileLayerRDD[SpatialKey] = ContextRDD(tiledRaster, rasterMetaData)

        //Removing other RDD
        rasterRDD.unpersist()
        tiledRaster.unpersist()
        for (theRun <- 1 to 3) {
          //val theRun = 1
          for (v <- vectorDatasets) {
            // val file: String = "/home/david/shapefiles/4326/states_2.geojson" //"data/censusMetroNew.geojson"
            var jsonPath = v.theBasePath + "/" + r.srid + "/" + v.theJSON
            //val jsonPath = vectorDatasets(0)._2
            val theJSON = scala.io.Source.fromFile(jsonPath).getLines.mkString
            case class Attributes(NAME: String, LSAD: String, AFFGEOID: String, ALAND: Int, AWATER: Int, ID: Int)
            implicit val boxedToRead = jsonFormat6(Attributes)

            // GeoTrellis does not handle both multipolygons and polgyons in the same function
            val multiPolygons: Map[String, MultiPolygonFeature[Attributes]] = theJSON.parseGeoJson[JsonFeatureCollectionMap].getAllMultiPolygonFeatures[Attributes]
            val polygons: Map[String, PolygonFeature[Attributes]] = theJSON.parseGeoJson[JsonFeatureCollectionMap].getAllPolygonFeatures[Attributes]

            //Potential object for writing outvalues
            var ZonalStats = new ListBuffer[Map[String, (Int, Int, Double)]]()

            var zonalStatsStart = System.currentTimeMillis()

            val multiGeom = multiPolygons.mapValues(x => x.geom)
            val multiHistogram = multiGeom.mapValues(x => rasterTileLayerRDD.polygonalHistogram(x))
            val multiPolyStats = multiHistogram.mapValues(x => x.statistics.toList)
            for (k <- multiPolyStats.keys) {
              println(k, multiPolyStats(k))
            }

            /*        for (i<-0 to theMultiPolygonKeys.length-1){

              //var geom = multiPolygons.get(theMultiPolygonKeys(0).toString).get.geom
              var geom = multiPolygons.get(theMultiPolygonKeys(i).toString).get.geom
              var histogram = rasterTileLayerRDD.polygonalHistogram(geom)
              var theStats = histogram.statistics
              //var theMean = histogram.mean.min
              println(theMultiPolygonKeys(i).toString, theStats)

              //ZonalStats += Map(theMultiPolygonKeys(i).toString -> (theMin, theMax, theMean))
              //Map("x" -> 24, "y" -> 25, "z" -> 26)

            }*/


            var zonalStatsStop = System.currentTimeMillis()
            var multiPolygonTime = zonalStatsStop - zonalStatsStart
            println("Time to complete multipolygons: ", multiPolygonTime)
            println("*********** Finished multipolygons ***************")

            zonalStatsStart = System.currentTimeMillis()
            val polyGeom = polygons.mapValues(x => x.geom)
            val polyHistogram = polyGeom.mapValues(x => rasterTileLayerRDD.polygonalHistogram(x))
            //val polygonMeans = histogram.mapValues(x => x.mean.min)
            //val polyStats = histogram.mapValues(x => x.statistics)
            //val (polyMin, polyMax) = histogram.mapValues(x => x.minMaxValues.min)
            //val polyMin = histogram.mapValues(x => x.minMaxValues.min._1)
            //val polyMax = histogram.mapValues(x => x.minMaxValues.min._2)
            val polyStats = polyHistogram.mapValues(x => x.statistics.toList) //count.min._1)
            for (k <- polyStats.keys) {
              println(k, polyStats(k))
            }



            /*        for (i<-0 to thePolygonKeys.length-1){

            var geom = polygons.get(thePolygonKeys(i).toString).get.geom
            var histogram = rasterTileLayerRDD.polygonalHistogram(geom)
            var(theMin, theMax) = histogram.minMaxValues.min
            var theMean = histogram.mean.min
            println(thePolygonKeys(i).toString, theMin, theMax, theMean)

            ZonalStats += Map(thePolygonKeys(i).toString -> (theMin, theMax, theMean))


          }*/

            zonalStatsStop = System.currentTimeMillis()
            var polygonTime = zonalStatsStop - zonalStatsStart
            println("Time to complete polygons: ", polygonTime)
            var totalTime = polygonTime + multiPolygonTime
            println(s"Total Time to complete: $totalTime for $r.name with tilesize $tilesize on vector $v.name")

            writer.write("polygonal_summary,$r.name,$tilesize,$v.name,$totalTime,$multiPolygonTime, $polygonTime, $theRun\n")


            /*        val multiPolygonStats = ZonalStats.toList
          val thePolygonKeys = polygons.keys.toList*/
            // ZonalStats.toList.foreach(writer.write)

          } //vector
        } // run
      }//tile
    }//raster


    sc.stop()
  }

}
