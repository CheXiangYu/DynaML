/*
Copyright 2015 Mandar Chandorkar

Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
* */
package io.github.mandar2812.dynaml.models.gp

import breeze.linalg.{DenseMatrix, DenseVector, cholesky, trace}
import breeze.numerics.{log, sqrt}
import io.github.mandar2812.dynaml.algebra._
import io.github.mandar2812.dynaml.algebra.PartitionedMatrixOps._
import io.github.mandar2812.dynaml.algebra.PartitionedMatrixSolvers._
import io.github.mandar2812.dynaml.kernels.{DiracKernel, LocalScalarKernel, SVMKernel}
import io.github.mandar2812.dynaml.models.{ContinuousProcess, SecondOrderProcess}
import io.github.mandar2812.dynaml.optimization.GloballyOptWithGrad
import io.github.mandar2812.dynaml.pipes.DataPipe
import io.github.mandar2812.dynaml.probability.MultGaussianPRV
import org.apache.log4j.Logger

import scala.reflect.ClassTag

/**
  * Single-Output Gaussian Process Regression Model
  * Performs gp/spline smoothing/regression with
  * vector inputs and a singular scalar output.
  *
  * @tparam T The data structure holding the training data.
  *
  * @tparam I The index set over which the Gaussian Process
  *           is defined.
  *           e.g  1) I = Double when implementing GP time series
  *                2) I = DenseVector when implementing GP regression
  *
  */
abstract class AbstractGPRegressionModel[T, I](
  cov: LocalScalarKernel[I], n: LocalScalarKernel[I],
  data: T, num: Int, meanFunc: DataPipe[I, Double] = DataPipe((_:I) => 0.0))(implicit ev: ClassTag[I])
  extends ContinuousProcess[T, I, Double, MultGaussianPRV]
  with SecondOrderProcess[T, I, Double, Double, DenseMatrix[Double], MultGaussianPRV]
  with GloballyOptWithGrad {

  private val logger = Logger.getLogger(this.getClass)

  /**
   * The GP is taken to be zero mean, or centered.
   * This is ensured by standardization of the data
   * before being used for further processing.
   *
   * */
  override val mean: DataPipe[I, Double] = meanFunc

  override val covariance = cov

  val noiseModel = n

  override protected val g: T = data

  val npoints = num

  protected var blockSize = 1000

  def blockSize_(b: Int): Unit = {
    blockSize = b
  }

  def _blockSize: Int = blockSize

  protected var (caching, kernelMatrixCache)
  : (Boolean, DenseMatrix[Double]) = (false, null)

  protected var partitionedKernelMatrixCache: PartitionedPSDMatrix = _

  /**
    * Set the model "state" which
    * contains values of its hyper-parameters
    * with respect to the covariance and noise
    * kernels.
    * */
  def setState(s: Map[String, Double]): this.type = {
    covariance.setHyperParameters(s)
    noiseModel.setHyperParameters(s)
    current_state = covariance.state ++ noiseModel.state
    this
  }

  override protected var hyper_parameters: List[String] =
    covariance.hyper_parameters ++ noiseModel.hyper_parameters

  override protected var current_state: Map[String, Double] =
    covariance.state ++ noiseModel.state

  /**
    * Returns a [[DataPipe]] which calculates the energy of data: [[T]].
    * See: [[energy]] below.
    * */
  def calculateEnergyPipe(h: Map[String, Double], options: Map[String, String]) = DataPipe((dataset: T) => {
    setState(h)
    val training = dataAsIndexSeq(dataset)
    val trainingLabels = PartitionedVector(
      dataAsSeq(dataset).toStream.map(_._2),
      training.length.toLong, _blockSize
    )

    val trainingMean = PartitionedVector(
      dataAsSeq(dataset).toStream.map(_._1).map(mean(_)),
      training.length.toLong, _blockSize
    )

    val effectiveTrainingKernel: LocalScalarKernel[I] = this.covariance + this.noiseModel

    effectiveTrainingKernel.setBlockSizes((_blockSize, _blockSize))

    val kernelTraining: PartitionedPSDMatrix =
      effectiveTrainingKernel.buildBlockedKernelMatrix(training, training.length)

    AbstractGPRegressionModel.logLikelihood(trainingLabels - trainingMean, kernelTraining)
  })

  /**
    * Calculates the energy of the configuration,
    * in most global optimization algorithms
    * we aim to find an approximate value of
    * the hyper-parameters such that this function
    * is minimized.
    *
    * @param h The value of the hyper-parameters in the configuration space
    * @param options Optional parameters about configuration
    * @return Configuration Energy E(h)
    *
    * In this particular case E(h) = -log p(Y|X,h)
    * also known as log likelihood.
    **/
  override def energy(h: Map[String, Double], options: Map[String, String]): Double =
    calculateEnergyPipe(h, options)(g)

  /**
    * Returns a [[DataPipe]] which calculates the gradient of the energy, E(.) of data: [[T]]
    * with respect to the model hyper-parameters.
    * See: [[gradEnergy]] below.
    * */
  def calculateGradEnergyPipe(h: Map[String, Double]) = DataPipe((dataset: T) => {
    try {
      covariance.setHyperParameters(h)
      noiseModel.setHyperParameters(h)

      val training = dataAsIndexSeq(dataset)
      val trainingLabels = PartitionedVector(
        dataAsSeq(dataset).toStream.map(_._2),
        training.length.toLong, _blockSize
      )

      val trainingMean = PartitionedVector(
        dataAsSeq(dataset).toStream.map(_._1).map(mean(_)),
        training.length.toLong, _blockSize
      )

      val effectiveTrainingKernel: LocalScalarKernel[I] = covariance + noiseModel

      effectiveTrainingKernel.setBlockSizes((blockSize, blockSize))
      val hParams = effectiveTrainingKernel.effective_hyper_parameters

      val gradMatrices = SVMKernel.buildPartitionedKernelGradMatrix(
        training, training.length, _blockSize, _blockSize,
        hParams, (x: I, y: I) => effectiveTrainingKernel.evaluate(x,y),
        (hy: String) => (x: I, y: I) => effectiveTrainingKernel.gradient(x,y)(hy))

      val kernelTraining: PartitionedPSDMatrix = gradMatrices("kernel-matrix")

      val Lmat = bcholesky(kernelTraining)

      val alpha = Lmat.t \\ (Lmat \\ (trainingLabels-trainingMean))

      hParams.map(h => {
        //build kernel derivative matrix
        val kernelDerivative: PartitionedMatrix = gradMatrices(h)
        //Calculate gradient for the hyper parameter h
        val grad: PartitionedMatrix =
          alpha*alpha.t*kernelDerivative - (Lmat.t \\ (Lmat \\ kernelDerivative))

        (h.split("/").tail.mkString("/"), btrace(grad))
      }).toMap

    } catch {
      case _: breeze.linalg.NotConvergedException =>
        covariance.effective_hyper_parameters.map(h => (h, Double.NaN)).toMap ++
          noiseModel.effective_hyper_parameters.map(h => (h, Double.NaN)).toMap
    }

  })

  /**
    * Calculates the gradient energy of the configuration and
    * subtracts this from the current value of h to yield a new
    * hyper-parameter configuration.
    *
    * Over ride this function if you aim to implement a gradient based
    * hyper-parameter optimization routine like ML-II
    *
    * @param h The value of the hyper-parameters in the configuration space
    * @return Gradient of the objective function (marginal likelihood) as a Map
    **/
  override def gradEnergy(h: Map[String, Double]): Map[String, Double] = calculateGradEnergyPipe(h)(g)

  /**
   * Calculates posterior predictive distribution for
   * a particular set of test data points.
   *
   * @param test A Sequence or Sequence like data structure
   *             storing the values of the input patters.
   **/
  override def predictiveDistribution[U <: Seq[I]](test: U):
  MultGaussianPRV = {

    logger.info("Calculating posterior predictive distribution")
    //Calculate the kernel matrix on the training data
    val training = dataAsIndexSeq(g)
    val trainingLabels = PartitionedVector(
      dataAsSeq(g).toStream.map(_._2),
      training.length.toLong, _blockSize
    )

    val priorMeanTest = PartitionedVector(
      test.map(mean(_))
        .grouped(_blockSize)
        .zipWithIndex.map(c => (c._2.toLong, DenseVector(c._1.toArray)))
        .toStream,
      test.length.toLong)

    val trainingMean = PartitionedVector(
      dataAsSeq(g).toStream.map(_._1).map(mean(_)),
      training.length.toLong, _blockSize
    )

    val effectiveTrainingKernel: LocalScalarKernel[I] = covariance + noiseModel
    effectiveTrainingKernel.setBlockSizes((blockSize, blockSize))

    val smoothingMat = if(!caching) {
      logger.info("---------------------------------------------------------------")
      logger.info("Calculating covariance matrix for training points")
      SVMKernel.buildPartitionedKernelMatrix(training,
        training.length, _blockSize, _blockSize,
        effectiveTrainingKernel.evaluate)
    } else {
      logger.info("** Using cached training matrix **")
      partitionedKernelMatrixCache
    }

    logger.info("---------------------------------------------------------------")
    logger.info("Calculating covariance matrix for test points")
    val kernelTest = SVMKernel.buildPartitionedKernelMatrix(
      test, test.length.toLong,
      _blockSize, _blockSize, covariance.evaluate)

    logger.info("---------------------------------------------------------------")
    logger.info("Calculating covariance matrix between training and test points")
    val crossKernel = SVMKernel.crossPartitonedKernelMatrix(
      training, test,
      _blockSize, _blockSize,
      covariance.evaluate)

    //Calculate the predictive mean and co-variance
    val Lmat: LowerTriPartitionedMatrix = bcholesky(smoothingMat)

    val alpha: PartitionedVector = Lmat.t \\ (Lmat \\ (trainingLabels-trainingMean))

    val v: PartitionedMatrix = Lmat \\ crossKernel

    val varianceReducer: PartitionedMatrix = v.t * v

    //Ensure that the variance reduction is symmetric
    val adjustedVarReducer: PartitionedMatrix = (varianceReducer.L + varianceReducer.L.t).map(bm =>
      if(bm._1._1 == bm._1._2) (bm._1, bm._2*(DenseMatrix.eye[Double](bm._2.rows)*0.5))
      else bm)

    val reducedVariance: PartitionedPSDMatrix =
      new PartitionedPSDMatrix(
        (kernelTest - adjustedVarReducer).filterBlocks(c => c._1 <= c._2),
        kernelTest.rows, kernelTest.cols)


    MultGaussianPRV(test.length.toLong, _blockSize)(
      priorMeanTest + crossKernel.t * alpha,
      reducedVariance)
  }

  /**
    * Draw three predictions from the posterior predictive distribution
    * 1) Mean or MAP estimate Y
    * 2) Y- : The lower error bar estimate (mean - sigma*stdDeviation)
    * 3) Y+ : The upper error bar. (mean + sigma*stdDeviation)
    **/
  def predictionWithErrorBars[U <: Seq[I]](testData: U, sigma: Int):
  Seq[(I, Double, Double, Double)] = {

    val posterior = predictiveDistribution(testData)
    val postcov = posterior.covariance
    val postmean = posterior.mu
    val varD: PartitionedVector = bdiag(postcov)
    val stdDev = varD._data.map(c => (c._1, sqrt(c._2))).map(_._2.toArray.toStream).reduceLeft((a, b) => a ++ b)
    val mean = postmean._data.map(_._2.toArray.toStream).reduceLeft((a, b) => a ++ b)

    logger.info("Generating error bars")
    val preds = (mean zip stdDev).map(j => (j._1, j._1 - sigma*j._2, j._1 + sigma*j._2))
    (testData zip preds).map(i => (i._1, i._2._1, i._2._2, i._2._3))
  }


  override def predict(point: I): Double = predictionWithErrorBars(Seq(point), 1).head._2


  /**
    * Cache the training kernel and noise matrices
    * for fast access in future predictions.
    * */
  override def persist(state: Map[String, Double]): Unit = {
    setState(state)
    val effectiveTrainingKernel: LocalScalarKernel[I] = covariance + noiseModel
    effectiveTrainingKernel.setBlockSizes((blockSize, blockSize))

    val training = dataAsIndexSeq(g)
    partitionedKernelMatrixCache = SVMKernel.buildPartitionedKernelMatrix(training,
      training.length, _blockSize, _blockSize,
      effectiveTrainingKernel.evaluate)
    caching = true

  }

  /**
    * Forget the cached kernel & noise matrices.
    * */
  def unpersist(): Unit = {
    kernelMatrixCache = null
    partitionedKernelMatrixCache = null
    caching = false
  }

}

object AbstractGPRegressionModel {

  /**
    * Calculate the marginal log likelihood
    * of the training data for a pre-initialized
    * kernel and noise matrices.
    *
    * @param trainingData The function values assimilated as a [[DenseVector]]
    *
    * @param kernelMatrix The kernel matrix of the training features
    *
    * */
  def logLikelihood(trainingData: DenseVector[Double],
                    kernelMatrix: DenseMatrix[Double]): Double = {

    val smoothingMat = kernelMatrix

    try {
      val Lmat = cholesky(smoothingMat)
      val alpha = Lmat.t \ (Lmat \ trainingData)

      0.5*((trainingData dot alpha) +
        trace(log(Lmat)) +
        trainingData.length*math.log(2*math.Pi))
    } catch {
      case _: breeze.linalg.NotConvergedException => Double.PositiveInfinity
      case _: breeze.linalg.MatrixNotSymmetricException => Double.PositiveInfinity
    }
  }


  /**
    * Calculate the marginal log likelihood
    * of the training data for a pre-initialized
    * kernel and noise matrices.
    *
    * @param trainingData The function values assimilated as a [[DenseVector]]
    *
    * @param kernelMatrix The kernel matrix of the training features
    *
    * */
  def logLikelihood(trainingData: PartitionedVector,
                    kernelMatrix: PartitionedPSDMatrix): Double = {

    val smoothingMat = kernelMatrix

    try {
      val Lmat = bcholesky(smoothingMat)
      val alpha: PartitionedVector = Lmat.t \\ (Lmat \\ trainingData)

      val d: Double = trainingData dot alpha

      0.5*(d +
        btrace(blog(Lmat)) +
        trainingData.rows*math.log(2*math.Pi))
    } catch {
      case _: breeze.linalg.NotConvergedException => Double.PositiveInfinity
      case _: breeze.linalg.MatrixNotSymmetricException => Double.PositiveInfinity
    }
  }


  def apply[M <: AbstractGPRegressionModel[Seq[(DenseVector[Double], Double)],
    DenseVector[Double]]](data: Seq[(DenseVector[Double], Double)],
                          cov: LocalScalarKernel[DenseVector[Double]],
                          noise: LocalScalarKernel[DenseVector[Double]] = new DiracKernel(1.0),
                          order: Int = 0, ex: Int = 0,
                          meanFunc: DataPipe[DenseVector[Double], Double] = DataPipe(_ => 0.0)): M = {
    assert(ex >= 0 && order >= 0, "Non Negative values for order and ex")
    if(order == 0) new GPRegression(cov, noise, data).asInstanceOf[M]
    else if(order > 0 && ex == 0) new GPNarModel(order, cov, noise, data).asInstanceOf[M]
    else new GPNarXModel(order, ex, cov, noise, data).asInstanceOf[M]
  }

  def apply[T, I](
    cov: LocalScalarKernel[I],
    noise: LocalScalarKernel[I],
    meanFunc: DataPipe[I, Double])(
    trainingdata: T, num: Int)(
    implicit transform: DataPipe[T, Seq[(I, Double)]], ct: ClassTag[I]) =
    new AbstractGPRegressionModel[T, I](cov, noise, trainingdata, num, meanFunc) {
      /**
        * Convert from the underlying data structure to
        * Seq[(I, Y)] where I is the index set of the GP
        * and Y is the value/label type.
        **/
      override def dataAsSeq(data: T) = transform(data)
    }
}