#!/bin/bash

#Script para lanzar el pipeline con 7 argumentos
spark-submit --class es.dmr.uimp.realtime.InvoicePipeline \
  --master local[4] \
  target/scala-2.11/anomalyDetection-assembly-1.0.jar \
  ./clustering \
  ./threshold \
  ./clustering_bisect \
  ./threshold_bisect \
  pipeline-group-test \
  purchases \
  localhost:9092