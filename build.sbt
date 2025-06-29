name := "anomalyDetection"

version := "1.0"

scalaVersion := "2.11.8"

// Usaremos la versión de Spark que tu sistema tiene instalada (2.4.4)
val sparkVersion = "2.4.4"

libraryDependencies ++= Seq(
  // === Dependencias de Spark Core (se alinean a la versión 2.4.4) ===
  "org.apache.spark" %% "spark-core" % sparkVersion % "provided",
  "org.apache.spark" %% "spark-sql" % sparkVersion % "provided",
  "org.apache.spark" %% "spark-mllib" % sparkVersion % "provided",

  // === CORRECCIÓN CRÍTICA: Se reemplaza la librería de Kafka 0.8 por la de 0.10 ===
  // Esta es la librería moderna y compatible para DStreams.
  "org.apache.spark" %% "spark-streaming-kafka-0-10" % sparkVersion,

  // === LIMPIEZA: Se elimina la librería "spark-csv" obsoleta ===
  // Ya no es necesaria, usaremos la funcionalidad nativa de Spark.

  // Otras dependencias
  "org.slf4j" % "slf4j-simple" % "1.7.25", // Versión más común con Spark 2.4
  "com.univocity" % "univocity-parsers" % "2.5.9" // Versión más reciente compatible
)

resolvers += "Cloudera" at "https://repository.cloudera.com/artifactory/cloudera-repos/"

// La estrategia de merge se mantiene igual, está bien.
assemblyMergeStrategy in assembly := {
  case m if m.toLowerCase.endsWith("manifest.mf")      => MergeStrategy.discard
  case m if m.toLowerCase.matches("meta-inf.*\\.sf$")   => MergeStrategy.discard
  case "log4j.properties"                              => MergeStrategy.discard
  case m if m.toLowerCase.startsWith("meta-inf/services/") => MergeStrategy.filterDistinctLines
  case "reference.conf"                                => MergeStrategy.concat
  case _                                               => MergeStrategy.first
}