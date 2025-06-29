package es.dmr.uimp.clustering

import es.dmr.uimp.clustering.Clustering._

import org.apache.spark.{SparkConf, SparkContext}

import org.apache.spark.mllib.clustering.{BisectingKMeans, BisectingKMeansModel, KMeans, KMeansModel}
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import scala.util.Try

object KMeansClusterInvoices {

  def main(args: Array[String]) {

    import Clustering._

    val sparkConf = new SparkConf().setAppName("ClusterInvoices")
    val sc = new SparkContext(sparkConf)

    // load data
    val df_data = loadData(sc, args(0))

    val filtered = filterData(df_data)
    val featurized = featurizeData(filtered)
    featurized.printSchema()


    // Transform in a dataset for MLlib
    val dataset = toDataset(featurized)

    // We are going to use this a lot (cache it)
    dataset.cache()

    // Print a sampl
    dataset.take(5).foreach(println)

    val model = trainModelKMeans(dataset)
    // Save model
    model.save(sc, args(1))

    // Save threshold
    val distances = dataset.map(d => distToCentroidKMeans(d, model))
    val threshold = distances.top(2000).last // set the last of the furthest 2000 data points as the threshold

    saveThreshold(threshold, args(2))
  }

  /**
   * Train a KMean model using invoice data.
   */
  def trainModelKMeans(data: RDD[Vector]): KMeansModel = {

    val models = 1 to 20 map { k =>
      val kmeans = new KMeans()
      kmeans.setK(k) // find that one center
      kmeans.run(data)
    }

    val costs = models.map(model => model.computeCost(data))

    val selected = elbowSelection(costs, 0.7)
    System.out.println("Selecting model: " + models(selected).k)
    models(selected)
  }

  /**
   * Calculate distance between data point to centroid.
   */
  def distToCentroidKMeans(datum: Vector, model: KMeansModel): Double = {
    val centroid = model.clusterCenters(model.predict(datum)) // if more than 1 center
    Vectors.sqdist(datum, centroid)
  }
}
object BisectionKMeansClusterInvoices {

  def main(args: Array[String]) {

    import Clustering._

    val sparkConf = new SparkConf().setAppName("ClusterInvoices")
    val sc = new SparkContext(sparkConf)

    // load data
    val df_data = loadData(sc, args(0))

    val filtered = filterData(df_data)
    val featurized = featurizeData(filtered)

    featurized.printSchema()


    // Transform in a dataset for MLlib
    val dataset = toDataset(featurized)

    // We are going to use this a lot (cache it)
    dataset.cache()

    // Print a sampl
    dataset.take(5).foreach(println)

    val model = trainModelBisection(dataset)
    // Save model
    model.save(sc, args(1))

    // Save threshold
    val distances = dataset.map(d => distToCentroidBisection(d, model))
    val threshold = distances.top(2000).last

    saveThreshold(threshold, args(2))
  }

  def trainModelBisection(data: RDD[Vector]): BisectingKMeansModel = {
    val models = 2 to 20 map { k =>
      val bkm = new BisectingKMeans()
      bkm.setK(k)
      bkm.run(data)
    }
    val costs = models.map(model => model.computeCost(data))
    val selected = elbowSelection(costs, 0.7)
    System.out.println("Selecting model: " + models(selected).k)
    models(selected)
  }

  def distToCentroidBisection(vector: Vector, model: BisectingKMeansModel): Double = {
    val centroid = model.clusterCenters(model.predict(vector))
    Vectors.sqdist(vector, centroid)
  }
}



