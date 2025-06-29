#!/bin/bash

# Rutas archivos
TRAINING_DATA_FILE="./resources/training.csv"
KMEANS_MODEL_OUTPUT="./clustering"
KMEANS_THRESHOLD_OUTPUT_PATH="./threshold"

BISECTING_KMEANS_MODEL_OUTPUT="./clustering_bisect"
BISECTING_KMEANS_THRESHOLD_OUTPUT="./threshold_bisect"

#Indica el inicio del entrenamiento
echo "Iniciando entrenamiento..."

#----------Entrenamiento KMeans
echo "Modelo KMEANS"

./execute.sh es.dmr.uimp.clustering.KMeansClusterInvoices \
  "${TRAINING_DATA_FILE}" \
  "${KMEANS_MODEL_OUTPUT}" \
  "${KMEANS_THRESHOLD_OUTPUT_PATH}"
echo "Entrenamiento KMeans completado"

#----------Entrenamiento BisectingKMeans
echo "Modelo BisectingKMeans"

./execute.sh es.dmr.uimp.clustering.BisectionKMeansClusterInvoices \
  "${TRAINING_DATA_FILE}" \
  "${BISECTING_KMEANS_MODEL_OUTPUT}" \
  "${BISECTING_KMEANS_THRESHOLD_OUTPUT}"
echo "Entrenamiento BisectionKMeans completado"
echo "Todos los entrenamientos han finalizado."
echo "Modelos KMeans guardados en: ${KMEANS_MODEL_OUTPUT_PATH}"
echo "Umbral KMeans guardado en: ${KMEANS_THRESHOLD_OUTPUT_PATH}"
echo "Modelos BisectingKMeans guardados en: ${BISECTING_KMEANS_MODEL_OUTPUT_PATH}"
echo "Umbral BisectingKMeans guardado en: ${BISECTING_KMEANS_THRESHOLD_OUTPUT_PATH}"
