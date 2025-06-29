
# Sistema de Detección de Anomalías en Facturas con Spark Streaming y Kafka

Este proyecto implementa un sistema de detección de facturas anómalas en tiempo real mediante Apache Spark Streaming y clustering no supervisado (KMeans y BisectingKMeans). Se utiliza Kafka para la ingesta y distribución de datos.

---

## Requisitos

- Apache Kafka y Zookeeper instalados y configurados
- Apache Spark 2.0.0
- Scala 2.11
- sbt (para compilar el proyecto)
- Datasets:
    - Entrenamiento en `./resources/training.csv`
    - Producción en `./resources/production.csv`
    - Envios online en `./resources/online_retail.csv`
---

## Estructura del Proyecto

- `start_training.sh`: Entrena los modelos KMeans y BisectingKMeans offline.
- `start_pipeline.sh`: Lanza el pipeline de detección en tiempo real.
- `productiondata.sh`: Simula el envío de compras a Kafka 
- `clustering/` y `clustering_bisect/`: Directorios donde se guardan los modelos entrenados.
- `threshold` y `threshold_bisect`: Archivos con umbrales para detección de anomalías para ambos modelos guardados.
- `es.dmr.uimp.clustering.train` : Script de entrenamiento de los modelos
- `es.dmr.uimp.clustering.Clustering` : Script de carga de datos y paso de pedido a factura
- `es.dmr.uimp.realtime.InvoicePipeline` : Flujo principal de las funcionalidades del proyecto
---

## Flujo de Ejecución

### 1️) Iniciar Servicios Kafka

**Terminal 1 - Iniciar Zookeeper:**
```bash
bin/zookeeper-server-start.sh config/zookeeper.properties
```

**Terminal 2 – Iniciar Kafka Broker:**
```bash
bin/kafka-server-start.sh config/server.properties
```

---

### 2️) Crear Topics Requeridos (una sola vez)

```bash
bin/kafka-topics.sh --create --topic purchases --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1
bin/kafka-topics.sh --create --topic facturas_erroneas --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1
bin/kafka-topics.sh --create --topic cancelaciones --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1
bin/kafka-topics.sh --create --topic anomalias_kmeans --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1
bin/kafka-topics.sh --create --topic anomalias_bisect_kmeans --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1
```

---

### 3️) Entrenamiento de Modelos Offline

**Terminal 3 – Ejecutar Entrenamiento:**
```bash
chmod +x start_training.sh
./start_training.sh
```

Esto entrenará los modelos de clustering y guardará:
- Modelos: `./clustering/`, `./clustering_bisect/`
- Umbrales: `./threshold`, `./threshold_bisect`

---

### 4️) Compilar el Proyecto 

Utilizar si se realizan cambios en los scripts

```bash
sbt clean assembly
```

---

### 5️) Iniciar el Pipeline de Detección

**Terminal 4 – Lanzar pipeline de Spark Streaming:**
```bash
chmod +x start_pipeline.sh
./start_pipeline.sh
```

Si reinicias la sesión o cambias el código, ejecuta también:
```bash
rm -rf ./spark_checkpoints_streaming
```

---

### 6️) Simular Datos de Compra

**Terminal 5 – Simular envío al topic `purchases`:**
```bash
chmod +x productiondata.sh
./productiondata.sh
```

Este script enviará las líneas de `production.csv` al topic `purchases`.

---

### 7️) Observar Resultados

**En otras terminales puedes observar los resultados en tiempo real:**

```bash
# Ver facturas erróneas
bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic facturas_erroneas --from-beginning

# Ver anomalías detectadas por KMeans
bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic anomalias_kmeans --from-beginning

# Ver anomalías detectadas por BisectingKMeans
bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic anomalias_bisect_kmeans --from-beginning

# Ver número de cancelaciones
bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic cancelaciones --from-beginning
```

---

## Información extra

- El sistema utiliza `mapWithState` y timeout para determinar cuándo una factura está completa.
- Se detectan cancelaciones en el rango de los últimos 8 minutos, actualizada cada minuto.
- El sistema soporta evaluación A/B de dos modelos de clustering.

---

