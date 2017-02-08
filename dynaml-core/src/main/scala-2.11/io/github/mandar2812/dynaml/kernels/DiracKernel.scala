package io.github.mandar2812.dynaml.kernels

import breeze.linalg.{DenseMatrix, DenseVector, norm}

/**
  * Dirac kernel is equivalent to the
  * classical Dirac delta function scaled by
  * a hyper-parameter called the noise level.
  *
  * K(x,y) = noise*DiracDelta(x,y)
  */
class DiracKernel(private var noiseLevel: Double = 1.0)
  extends SVMKernel[DenseMatrix[Double]]
  with LocalSVMKernel[DenseVector[Double]]
  with Serializable {

  override val hyper_parameters = List("noiseLevel")

  state = Map("noiseLevel" -> noiseLevel)

  def setNoiseLevel(d: Double): Unit = {
    this.state += ("noiseLevel" -> d)
    this.noiseLevel = d
  }

  override def evaluateAt(
    config: Map[String, Double])(
    x: DenseVector[Double],
    y: DenseVector[Double]): Double =
    if (norm(x-y, 2) == 0) math.abs(config("noiseLevel"))*1.0 else 0.0

  override def gradientAt(
    config: Map[String, Double])(
    x: DenseVector[Double],
    y: DenseVector[Double]): Map[String, Double] =
    Map("noiseLevel" -> 1.0*evaluateAt(config)(x,y)/math.abs(config("noiseLevel")))

  override def buildKernelMatrix[S <: Seq[DenseVector[Double]]](mappedData: S,
                                                                length: Int)
  : KernelMatrix[DenseMatrix[Double]] =
    new SVMKernelMatrix(DenseMatrix.eye[Double](length)*state("noiseLevel"), length)

}

class MAKernel(private var noiseLevel: Double = 1.0)
  extends LocalSVMKernel[Double]
  with Serializable {
  override val hyper_parameters = List("noiseLevel")

  state = Map("noiseLevel" -> noiseLevel)

  def setNoiseLevel(d: Double): Unit = {
    this.state += ("noiseLevel" -> d)
    this.noiseLevel = d
  }

  override def evaluateAt(
    config: Map[String, Double])(
    x: Double,
    y: Double): Double =
    if (x-y == 0.0) math.abs(config("noiseLevel"))*1.0 else 0.0

  override def gradientAt(
    config: Map[String, Double])(
    x: Double, y: Double): Map[String, Double] =
    Map("noiseLevel" -> 1.0*evaluateAt(config)(x,y)/math.abs(config("noiseLevel")))

  override def buildKernelMatrix[S <: Seq[Double]](mappedData: S,
                                                   length: Int)
  : KernelMatrix[DenseMatrix[Double]] =
    new SVMKernelMatrix(DenseMatrix.eye[Double](length)*state("noiseLevel"), length)

}

class CoRegDiracKernel extends LocalSVMKernel[Int] {
  override val hyper_parameters: List[String] = List()

  override def gradientAt(config: Map[String, Double])(x: Int, y: Int): Map[String, Double] = Map()

  override def evaluateAt(config: Map[String, Double])(x: Int, y: Int): Double =
    if(x == y) 1.0 else 0.0
}
