/*
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
package io.github.mandar2812.dynaml.examples

import java.io.File

import breeze.linalg.{DenseMatrix, DenseVector}
import com.github.tototoshi.csv.CSVWriter
import io.github.mandar2812.dynaml.kernels.{RBFKernel, SVMKernel}
import io.github.mandar2812.dynaml.models.KernelizedModel
import io.github.mandar2812.dynaml.models.svm.{KernelSparkModel, LSSVMSparkModel}
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}

/**
 * @author mandar2812 on 22/6/15.
 */

object TestDelve {
  def apply(nCores: Int = 4, prototypes: Int = 1, kernel: String,
            globalOptMethod: String = "gs", grid: Int = 7,
            step: Double = 0.45, logscale: Boolean = false,
            frac: Double, executors: Int = 1,
            paraFactor: Int = 2, csaIt: Int = 5): DenseVector[Double] = {
    val config = Map("file" -> "data/fried_delve.data", "delim" -> ",",
      "head" -> "false",
      "task" -> "regression")

    val configtest = Map("file" -> "data/fried_delve_test.data",
      "delim" -> ",",
      "head" -> "false")


    val conf = new SparkConf().setAppName("Fried Delve Synthetic Data").setMaster("local["+nCores+"]")

    conf.registerKryoClasses(Array(classOf[LSSVMSparkModel], classOf[KernelSparkModel],
      classOf[KernelizedModel[RDD[(Long, LabeledPoint)], RDD[LabeledPoint],
        DenseVector[Double], DenseVector[Double], Double, Int, Int]],
      classOf[SVMKernel[DenseMatrix[Double]]], classOf[RBFKernel],
      classOf[DenseVector[Double]],
      classOf[DenseMatrix[Double]]))

    val sc = new SparkContext(conf)

    val model = LSSVMSparkModel(config, sc)

    val nProt = if (kernel == "Linear") {
      model.npoints.toInt
    } else {
      if(prototypes > 0)
        prototypes
      else
        math.sqrt(model.npoints.toDouble).toInt
    }

    val (optModel, optConfig) = KernelizedModel.getOptimizedModel[RDD[(Long, LabeledPoint)],
      RDD[LabeledPoint], model.type](model, globalOptMethod,
        kernel, nProt, grid, step, logscale, csaIt)

    optModel.learn()

    val met = optModel.evaluate(configtest)

    met.print()
    println("Optimal Configuration: "+optConfig)
    val scale = if(logscale) "log" else "linear"

    val perf = met.kpi()
    val row = Seq(kernel, prototypes.toString, globalOptMethod,
      grid.toString, step.toString, scale,
      perf(0), perf(1), perf(2), optConfig.toString)

    val writer = CSVWriter.open(new File("data/resultsDelve.csv"), append = true)
    writer.writeRow(row)
    writer.close()
    optModel.unpersist
    perf
  }
}

