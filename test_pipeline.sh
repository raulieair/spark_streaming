#!/bin/bash

#Depurar el arranque del pipeline.

echo "--- INICIANDO SCRIPT DE PRUEBA ---"

# Definir cada argumento en una variable para mayor claridad.
# Asegúrate de que estas rutas y valores son correctos para tu entorno.
ARG1="./clustering"
ARG2="./threshold"
ARG3="./clustering_bisect"
ARG4="./threshold_bisect"
ARG5="localhost:2181"
ARG6="pipeline-group-test"
ARG7="purchases"
ARG8="1"
ARG9="localhost:9092"

# Imprimir los argumentos para verificar que el shell los está leyendo bien.
echo "Argumento 1 (modelFileKMeans): $ARG1"
echo "Argumento 2 (thresholdFileKMeans): $ARG2"
echo "Argumento 3 (modelFileBisect): $ARG3"
echo "Argumento 4 (thresholdFileBisect): $ARG4"
echo "Argumento 5 (zkQuorum): $ARG5"
echo "Argumento 6 (group): $ARG6"
echo "Argumento 7 (topics): $ARG7"
echo "Argumento 8 (numThreads): $ARG8"
echo "Argumento 9 (brokers): $ARG9"

echo ""
echo "--- EJECUTANDO SPARK-SUBMIT... ---"

# Ejecutar el comando spark-submit directamente, pasando los argumentos entre comillas.
spark-submit --class es.dmr.uimp.realtime.InvoicePipeline \
  --master local[4] \
  -- verbose \
  target/scala-2.11/anomalyDetection-assembly-1.0.jar \
  "$ARG1" \
  "$ARG2" \
  "$ARG3" \
  "$ARG4" \
  "$ARG5" \
  "$ARG6" \
  "$ARG7" \
  "$ARG8" \
  "$ARG9"

# Capturar y mostrar el código de salida de spark-submit. 0 = éxito, cualquier otro valor = error.
EXIT_CODE=$?
echo "--- SPARK-SUBMIT TERMINADO CON CÓDIGO DE SALIDA: $EXIT_CODE ---"