package es.dmr.uimp.realtime

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.mllib.clustering.{BisectingKMeansModel, KMeansModel}
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.kafka010.{ConsumerStrategies, KafkaUtils, LocationStrategies}
import java.text.SimpleDateFormat
import java.util.{Calendar, HashMap, Locale}
import org.apache.commons.lang3.StringUtils
import scala.util.Try
import scala.collection.mutable.ListBuffer

object InvoicePipeline {

  //Estructuras de Datos
  case class Purchase(invoiceNo: String, quantity: Int, invoiceDate: String,
                      unitPrice: Double, customerID: String, country: String)

  case class Invoice(invoiceNo: String, avgUnitPrice: Double, minUnitPrice: Double,
                     maxUnitPrice: Double, time: Double, numberItems: Double,
                     lastUpdated: Long, lines: Int, customerId: String) {
    def toVector: Vector = Vectors.dense(avgUnitPrice, minUnitPrice, maxUnitPrice, time, numberItems)
    override def toString: String = s"Invoice(invoiceNo=$invoiceNo, customerId=$customerId, lines=$lines, items=$numberItems)"
  }

  case class AggregatingInvoiceData(
                                     invoiceNo: String,
                                     purchasesList: ListBuffer[Purchase],
                                     var capturedCustomerId: Option[String],
                                     var firstPurchaseTimestamp: Long
                                   )

  //Constantes
  val invoiceDateFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm", Locale.ENGLISH)
  val outputTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
  val INVOICE_COMPLETION_TIMEOUT_MS = 60 * 1000 // 60 segundos


  // FUNCIÓN DE ACTUALIZACIÓN DE ESTADO
  // =======================================================================
  def updateInvoiceState(
                          invoiceNoKey: String,
                          newPurchasesOpt: Option[Seq[Purchase]],
                          stateOpt: State[AggregatingInvoiceData]
                        ): Option[Invoice] = {

    if (stateOpt.isTimingOut()) {
      // Cuando un estado expira,calculamos y devolvemos el valor final
      stateOpt.getOption.flatMap { aggregator =>
        if (aggregator.purchasesList.nonEmpty) {
          println(s"INFO: Factura ${aggregator.invoiceNo} ha expirado. Contiene ${aggregator.purchasesList.size} líneas. Calculando características.")
          val allPurchases = aggregator.purchasesList.toList
          val minPrice = allPurchases.map(_.unitPrice).min
          val maxPrice = allPurchases.map(_.unitPrice).max
          val sumPrices = allPurchases.map(_.unitPrice).sum
          val numLines = allPurchases.size
          val avgPrice = if (numLines > 0) sumPrices / numLines else 0.0
          val totalItems = allPurchases.map(_.quantity.toLong).sum
          val calendar = Calendar.getInstance(); calendar.setTimeInMillis(aggregator.firstPurchaseTimestamp)
          val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY).toDouble

          Some(Invoice(
            invoiceNo = aggregator.invoiceNo, avgUnitPrice = avgPrice, minUnitPrice = minPrice,
            maxUnitPrice = maxPrice, time = hourOfDay, numberItems = totalItems.toDouble,
            lastUpdated = System.currentTimeMillis(), lines = numLines, customerId = aggregator.capturedCustomerId.getOrElse("")
          ))
        } else { None }
      }
    } else {
      // Si no hay timeout, actualizamos el estado con las nuevas compras
      newPurchasesOpt.foreach { newPurchases =>
        val aggregator = stateOpt.getOption.getOrElse {
          val firstPurchase = newPurchases.head
          val initialTimestamp = Try(invoiceDateFormat.parse(firstPurchase.invoiceDate).getTime).getOrElse(System.currentTimeMillis())
          println(s"DEBUG: Creando nuevo estado para factura ${invoiceNoKey}...")
          AggregatingInvoiceData(invoiceNoKey, ListBuffer[Purchase](),
            if (StringUtils.isNotBlank(firstPurchase.customerID)) Some(firstPurchase.customerID) else None, initialTimestamp)
        }

        aggregator.purchasesList ++= newPurchases
        if (aggregator.capturedCustomerId.isEmpty) {
          newPurchases.find(p => StringUtils.isNotBlank(p.customerID)).foreach(p => aggregator.capturedCustomerId = Some(p.customerID))
        }
        stateOpt.update(aggregator)
      }
      None
    }
  }


  // MÉTODO PRINCIPAL
  // =================================================
  def main(args: Array[String]) {
    if (args.length < 7) {
      System.err.println("Uso: InvoicePipeline <modelFileKMeans> <thresholdFileKMeans> <modelFileBisect> <thresholdFileBisect> <groupId> <topics> <brokers>")
      System.exit(1)
    }
    val Array(modelFile, thresholdFile, modelFileBisect, thresholdFileBisect, group, topics, brokers) = args

    def functionToCreateContext(): StreamingContext = {
      val sparkConf = new SparkConf().setAppName("InvoicePipeline_Streaming")
      val sc = new SparkContext(sparkConf)
      val ssc = new StreamingContext(sc, Seconds(20))
      sc.setLogLevel("ERROR")
      ssc.checkpoint("./spark_checkpoints_streaming")

      println("INFO: Cargando modelos y umbrales...")
      val (kmeansModel, kmeansThreshold) = loadKMeansAndThreshold(sc, modelFile, thresholdFile)
      val (bisectionModel, bisectionThreshold) = loadBisectionKMeansAndThreshold(sc, modelFileBisect, thresholdFileBisect)
      val kmeansModelBC = sc.broadcast(kmeansModel)
      val kmeansThresholdBC = sc.broadcast(kmeansThreshold)
      val bisectionModelBC = sc.broadcast(bisectionModel)
      val bisectionThresholdBC = sc.broadcast(bisectionThreshold)
      val brokersBC = sc.broadcast(brokers)
      println("INFO: Modelos y umbrales cargados y difundidos.")

      val purchaseLinesDStream = connectToPurchases(ssc, brokers, group, topics)
      val purchasesDStream: DStream[Purchase] = purchaseLinesDStream.flatMap(parsePurchase)
      purchasesDStream.cache()

      //Lógica de Cancelaciones
      val canceledInvoicesCountDStream = purchasesDStream
        .filter(_.invoiceNo.startsWith("C")).map(_.invoiceNo)
        .transform(_.distinct()).countByWindow(Minutes(8), Minutes(1))

      canceledInvoicesCountDStream.foreachRDD { (rdd, time) =>
        if (!rdd.isEmpty()) {
          val count = rdd.first()
          if(count > 0) {
            println(s"INFO [${outputTimeFormat.format(time.milliseconds)}]: Total facturas canceladas en la ventana: $count")
            val producer = new KafkaProducer[String, String](kafkaConf(brokersBC.value))
            producer.send(new ProducerRecord[String, String]("cancelaciones", "total_cancelaciones", count.toString))
            producer.close()
          }
        }
      }

      //Agregación de Facturas
      val regularPurchases = purchasesDStream.filter(p => !p.invoiceNo.startsWith("C"))
      val keyedPurchases = regularPurchases.map(p => (p.invoiceNo, p)).groupByKey().mapValues(_.toSeq)
      val stateSpec = StateSpec.function(updateInvoiceState _).timeout(Seconds(INVOICE_COMPLETION_TIMEOUT_MS / 1000))
      val completedInvoicesDStream = keyedPurchases.mapWithState(stateSpec).flatMap(x => x)

      //Procesamiento de Facturas Completas
      completedInvoicesDStream.foreachRDD { (rdd, time) =>
        if (!rdd.isEmpty()) {
          println(s"\n--- [${outputTimeFormat.format(time.milliseconds)}] Se completaron ${rdd.count()} facturas. Procesando... ---")
          rdd.cache()

          val problematicInvoicesRdd = rdd.filter(invoice => StringUtils.isBlank(invoice.customerId))
          val validInvoicesRdd = rdd.filter(invoice => StringUtils.isNotBlank(invoice.customerId))

          if (!problematicInvoicesRdd.isEmpty()) {
            println(s"  -> Detectadas ${problematicInvoicesRdd.count()} facturas erróneas.")
            publishToKafka("facturas_erroneas")(brokersBC)(problematicInvoicesRdd.map(inv => (inv.invoiceNo, inv.toString)))
          }

          if (!validInvoicesRdd.isEmpty()) {
            validInvoicesRdd.cache()

            val kmeansAnomalies = validInvoicesRdd.filter { invoice => distToCentroidKMeans(invoice.toVector, kmeansModelBC.value) > kmeansThresholdBC.value }
            if (!kmeansAnomalies.isEmpty()) {
              println(s"  -> ¡ANOMALÍA DETECTADA (KMeans)! ${kmeansAnomalies.count()} facturas.")
              publishToKafka("anomalias_kmeans")(brokersBC)(kmeansAnomalies.map(inv => (inv.invoiceNo, inv.toString)))
            }

            val bisectionAnomalies = validInvoicesRdd.filter { invoice => distToCentroidBisection(invoice.toVector, bisectionModelBC.value) > bisectionThresholdBC.value }
            if (!bisectionAnomalies.isEmpty()) {
              println(s"  -> ¡ANOMALÍA DETECTADA (BisectingKMeans)! ${bisectionAnomalies.count()} facturas.")
              publishToKafka("anomalias_bisect_kmeans")(brokersBC)(bisectionAnomalies.map(inv => (inv.invoiceNo, inv.toString)))
            }

            validInvoicesRdd.unpersist()
          }
          rdd.unpersist()
        }
      }
      ssc
    }

    val ssc = StreamingContext.getOrCreate("./spark_checkpoints_streaming", functionToCreateContext _)

    ssc.start()
    ssc.awaitTermination()
  }

  //FUNCIONES

  def parsePurchase(line: String): Option[Purchase] = {
    Try {
      val fields = line.split(",", -1)
      if (fields.length == 8) {
        Some(Purchase(fields(0), fields(3).toInt, fields(4), fields(5).toDouble, fields(6), fields(7)))
      } else None
    }.toOption.flatten
  }

  def loadKMeansAndThreshold(sc: SparkContext, modelFile: String, thresholdFile: String): (KMeansModel, Double) = {
    (KMeansModel.load(sc, modelFile), Try(sc.textFile(thresholdFile).filter(_.nonEmpty).first().toDouble).getOrElse(Double.MaxValue))
  }

  def loadBisectionKMeansAndThreshold(sc: SparkContext, modelFile: String, thresholdFile: String): (BisectingKMeansModel, Double) = {
    (BisectingKMeansModel.load(sc, modelFile), Try(sc.textFile(thresholdFile).filter(_.nonEmpty).first().toDouble).getOrElse(Double.MaxValue))
  }

  def kafkaConf(brokers: String): HashMap[String, Object] = {
    val props = new HashMap[String, Object]()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
    props
  }

  def publishToKafka(topic: String)(kafkaBrokers: Broadcast[String])(rdd: RDD[(String, String)]): Unit = {
    rdd.foreachPartition { partition =>
      val producer = new KafkaProducer[String, String](kafkaConf(kafkaBrokers.value))
      partition.foreach(record => producer.send(new ProducerRecord[String, String](topic, record._1, record._2)))
      producer.close()
    }
  }

  def connectToPurchases(ssc: StreamingContext, brokers: String, groupId: String, topics: String): DStream[String] = {
    val topicsSet = topics.split(",").toSet
    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> brokers, "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer], "group.id" -> groupId,
      "auto.offset.reset" -> "latest", "enable.auto.commit" -> (false: java.lang.Boolean)
    )
    KafkaUtils.createDirectStream[String, String](ssc, LocationStrategies.PreferConsistent,
      ConsumerStrategies.Subscribe[String, String](topicsSet, kafkaParams)).map(_.value())
  }

  //Funciones de distancia añadidas por fallos al acceder desde Clustering
  def distToCentroidKMeans(datum: Vector, model: KMeansModel): Double = {
    Vectors.sqdist(datum, model.clusterCenters(model.predict(datum)))
  }

  def distToCentroidBisection(datum: Vector, model: BisectingKMeansModel): Double = {
    val centroid = model.clusterCenters(model.predict(datum))
    Vectors.sqdist(datum, centroid)
  }
}