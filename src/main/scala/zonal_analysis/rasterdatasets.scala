package zonal_analysis

object rasterdatasets {
  case class myRaster(name: String, thePath: String, pixelValue: Int, newPixel: Int, src: Int=4326)
}