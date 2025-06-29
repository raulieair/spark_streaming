package es.dmr.uimp.clustering

import java.io.{BufferedWriter, File, FileWriter}

import org.apache.commons.lang3.StringUtils
import org.apache.spark.SparkContext
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spark.sql.functions._

import scala.collection.mutable.ArrayBuffer

/**
  * Created by root on 3/12/17.
  */
object Clustering {
  /**
    * Load data from file, parse the data and normalize the data.
    */
  def loadData(sc: SparkContext, file : String) : DataFrame = {
    val sqlContext = new SQLContext(sc)

    // Function to extract the hour from the date string
    val gethour =  udf[Double, String]((date : String) => {
      var out = -1.0
      if (!StringUtils.isEmpty(date)) {
        val hour = date.substring(10).split(":")(0)
        if (!StringUtils.isEmpty(hour))
          out = hour.trim.toDouble
      }
      out
    })

    // Load the csv data
    val df = sqlContext.read
      .format(".csv")
      .option("header", "true") // Use first line of all files as header
      .option("inferSchema", "true") // Automatically infer data types
      .load(file)
      .withColumn("Hour", gethour(col("InvoiceDate")))

    df
  }

  def featurizeData(df : DataFrame) : DataFrame = {
    //Recibimos el df de loadData
    val df_feat = df.groupBy("InvoiceNo").agg(
      avg("UnitPrice").as("AvgUnitPrice"), //calculo de la media de precios
      min("UnitPrice").as("MinUnitPrice"), // Seleccionamos el precio mínimo
      max("UnitPrice").as("MaxUnitPrice"), // Seleccionamos el precio máximo
      first("Hour").as("Time"), // hora seleccionada en la anterior función
      sum("Quantity").as("NumberItems") // suma del total de cantidades
    )
    df_feat
  }

  def filterData(df : DataFrame) : DataFrame = {
   //Filter cancelations and invalid
    val filteredDf = df.filter(
      !col("InvoiceNo").startsWith("C") && // descartar facturas canceladas
        col("CustomerID").isNotNull && // descartar CustomerID nulo
        col("InvoiceDate").isNotNull //descartar InvoiceDate nulo
    )
    filteredDf
  }

  def toDataset(df: DataFrame): RDD[Vector] = {
    val data = df.select("AvgUnitPrice", "MinUnitPrice", "MaxUnitPrice", "Time", "NumberItems").rdd
      .map(row =>{
        val buffer = ArrayBuffer[Double]()
        buffer.append(row.getAs("AvgUnitPrice"))
        buffer.append(row.getAs("MinUnitPrice"))
        buffer.append(row.getAs("MaxUnitPrice"))
        buffer.append(row.getAs("Time"))
        buffer.append(row.getLong(4).toDouble)
        val vector = Vectors.dense(buffer.toArray)
        vector
      })

    data
  }

  def elbowSelection(costs: Seq[Double], ratio : Double): Int = {
    // Select the best model

    for (id <- 1 until costs.length) {
      val error_k = costs(id)     // error para k actual
      val error_k1 = costs(id - 1) // error para el k anterior

      if (error_k1 != 0){ // controlamos que k-1 no sea 0 para que no nos de error la división (no deberia)
        val currentRatio = error_k/error_k1

        if (currentRatio > ratio) {
          // El primer k que cumple la condición se devuelve y es el seleccionado
          return id
        }

      }
    }
    return 0
  }

  def saveThreshold(threshold : Double, fileName : String) = {
    val file = new File(fileName)
    val bw = new BufferedWriter(new FileWriter(file))
    // decide threshold for anomalies
    bw.write(threshold.toString) // last item is the threshold
    bw.close()
  }

}
