# Vigía AI

**Señales que se convierten en acción.**

Proyecto de **Los Gatos Programadores** para el Desafío 2:
**Anticiparse a los problemas antes de que ocurran.**

Consume métricas de Prometheus desde Kafka, guarda históricos en H2, detecta
anomalías y notifica alertas y recuperaciones en Slack. Incluye un dashboard
Thymeleaf y asistencia opcional mediante Spring AI y OpenAI.

## Requisitos

- JDK 21 o compatible con el proyecto; comprueba `java -version`.
- Internet para descargar dependencias la primera vez.
- Kafka accesible desde la aplicación, por defecto en `localhost:9092`.
- Topic `metricas-prometheus` con métricas JSON.
- Para datos reales: servicio temperatura, Prometheus y el proceso que publica
  sus métricas en Kafka funcionando.
- Opcional: webhook de Slack y credenciales de OpenAI.

Se incluye Maven Wrapper: no necesitas instalar Maven.
Este proyecto no incluye Docker Compose ni levanta Kafka, Prometheus o temperatura.

## Inicio rápido

Ejecuta desde la **raíz de este proyecto**, donde está `pom.xml`:

```bash
./mvnw spring-boot:run
```

En Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

Si aparece `Permission denied` en macOS/Linux:

```bash
chmod +x mvnw
./mvnw spring-boot:run
```

Abre **http://localhost:8091/**. El puerto actual está definido en
`src/main/resources/application.yaml`.

H2 crea automáticamente las tablas en `./data/metricas`. No necesitas instalar
una base de datos. Ejecutar desde otra carpeta cambia la ubicación relativa de
la base y puede hacer que parezca vacía.

Para detener el proceso en la terminal utiliza `Ctrl+C`.

### Cambiar puerto o broker

Ejemplo para ejecutar en el puerto 8086:

```bash
SERVER_PORT=8086 \
DASHBOARD_URL=http://localhost:8086 \
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
./mvnw spring-boot:run
```

`DASHBOARD_URL` es el enlace que se envía en Slack; cambiar `SERVER_PORT` no lo
actualiza automáticamente. Si otros equipos abrirán ese enlace, usa una dirección
accesible para ellos, no `localhost`.

## Configuración

| Variable | Valor predeterminado | Uso |
|---|---|---|
| `SERVER_PORT` | `8091` | Puerto de la aplicación |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker Kafka |
| `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/metricas;AUTO_SERVER=TRUE` | Base H2 persistente |
| `SLACK_WEBHOOK_URL` | Sin configurar en YAML | Webhook para notificaciones |
| `DASHBOARD_URL` | `http://localhost:8091` | Enlace del dashboard en los mensajes |
| `IA_HABILITADA` | `false` | Activar enriquecimiento del pronóstico con IA |
| `OPENAI_API_KEY` | Sin credencial real | Clave de OpenAI |
| `OPENAI_MODEL` | `gpt-4o-mini` | Modelo configurado en Spring AI |

El listener fija el topic `metricas-prometheus` y el grupo
`mi-grupo-de-consumo` en `MyConsumer.java`. Cambiar solo el grupo del YAML no
sobrescribe el declarado en `@KafkaListener`.

### Slack

Configura el webhook en el entorno de la terminal o del IDE antes de arrancar:

```bash
export SLACK_WEBHOOK_URL='REEMPLAZAR_POR_TU_WEBHOOK'
export DASHBOARD_URL='http://localhost:8091'
./mvnw spring-boot:run
```

Reemplaza el marcador por una URL válida. No publiques credenciales ni las
incluyas en Git.

Si existe `config/application.properties`, Spring lo carga al ejecutar desde
la raíz. Ese archivo está excluido de Git y puede contener la configuración local
del webhook. Revisa esa configuración antes de asumir que Slack está desactivado.
Si usa `${SLACK_WEBHOOK_URL:...}`, la variable del entorno reemplaza su valor local.

Para desactivar Slack explícitamente, incluso si hay un webhook local:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--app.slack.webhook-url="
```

Cada alerta nueva queda pendiente en H2 hasta que Slack confirme `ok`. Si falla
el envío, se reintenta en los siguientes análisis, hasta 50 pendientes por ciclo.
Un timeout puede causar mensajes duplicados si se perdió la confirmación.

### Spring AI / OpenAI

```bash
export IA_HABILITADA=true
export OPENAI_API_KEY='REEMPLAZAR_POR_TU_CLAVE'
export OPENAI_MODEL='gpt-4o-mini'
./mvnw spring-boot:run
```

Sin habilitar IA, el dashboard funciona con reglas. Con IA habilitada, al consultar
el dashboard se llama al modelo cuando el score de riesgo del servicio es
al menos 30. Se envía un resumen de señales con instrucciones SRE y se obtiene
una causa probable y una lista de acciones. Hay caché de dos minutos por servicio
y mismo score. Si la llamada falla, se conserva la explicación por reglas.

**La IA no decide cuándo enviar Slack.** Las alertas automáticas y sus acciones
se generan con reglas y estadística. El modelo enriquece el pronóstico de la UI.
Los porcentajes del dashboard son heurísticos, no probabilidades predictivas
validadas. No hay RAG, fine-tuning ni entrenamiento del modelo con H2.
Usar OpenAI implica enviar el resumen al proveedor externo y puede generar costos.

## Datos de Kafka

El broker debe anunciar una dirección accesible desde donde corre esta aplicación.
Si Java corre en el host, `localhost:9092` puede funcionar con el puerto publicado
de Docker. Si Java corre dentro de un contenedor, `localhost` apunta a ese mismo
contenedor: configura el broker y sus advertised listeners según tu red.

Formato de un mensaje:

```json
{
  "name": "process_cpu_usage",
  "timestamp": "2026-11-13T20:00:00Z",
  "value": "0.85",
  "labels": {
    "__name__": "process_cpu_usage",
    "job": "mi-servicio-temperature",
    "instance": "host.docker.internal:8080"
  }
}
```

Usa timestamps UTC actuales. El ejemplo solo documenta la estructura: muestras
antiguas no activan las reglas que requieren datos recientes. CPU y heap se reciben
como fracciones (`0.85` equivale a `85 %`).

Si tienes las herramientas CLI de Kafka instaladas:

```bash
kafka-topics.sh --bootstrap-server localhost:9092 --list
kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic metricas-prometheus \
  --max-messages 5
```

Si están dentro de Docker, ejecuta las herramientas equivalentes en tu contenedor;
su ruta depende de la imagen utilizada.

El consumidor ignora buckets de histogramas y valores no finitos. La UI descubre
los servicios a partir de métricas `up` con etiqueta `job`: publicar únicamente CPU
no basta para que aparezca una tarjeta.

## Cómo comprobar que funciona

1. Levanta Kafka y el pipeline de métricas de temperatura.
2. Inicia esta aplicación y abre el dashboard.
3. Comprueba que aparece `mi-servicio-temperature` y que llegan muestras actuales.
4. Apaga temperatura sin detener Prometheus ni la publicación de métricas:
   deben seguir llegando muestras `up=0`.
5. Con tres muestras `up=0`, se registra `SERVICIO_CAIDO` y se notifica a Slack
   si está configurado.
6. Levanta temperatura. Una muestra reciente `up=1` posterior a un `up=0` registra
   `SERVICIO_RECUPERADO` y notifica una vez por recuperación.

El análisis arranca con un retraso inicial de 30 segundos y se repite con una
espera de 60 segundos tras terminar cada ejecución. Con scrape cada 15 segundos,
una caída puede tardar aproximadamente 30–105 segundos en detectarse, más demoras
de procesamiento o entrega. No recibir eventos no se interpreta como una caída.

Endpoints disponibles:

```bash
# Recomendaciones registradas
curl http://localhost:8091/api/recomendaciones

# Ejecutar análisis ahora: puede guardar alertas y enviar mensajes reales a Slack
curl http://localhost:8091/api/analizar
```

El detalle de un servicio se abre desde su tarjeta o mediante
`/pronostico/mi-servicio-temperature`.

### Anomalías

Se evalúan CPU, heap tras GC, tendencia heap, duración del scrape, hilos/pool
y disco libre. Se combinan umbrales absolutos con cambios sostenidos frente a
los últimos **dos minutos**.

Se necesitan tres muestras consecutivas que abarquen al menos 30 segundos;
la comparación histórica requiere además cinco muestras previas que abarquen
60 segundos. Cada serie se separa por sus etiquetas. El silencio entre alertas
repetidas es de **dos minutos**, salvo escalamiento de severidad.

La duración del scrape no equivale a la latencia HTTP p99. La tendencia heap
del pronóstico UI aún consulta 30 minutos, mientras el detector de anomalías
trabaja con dos minutos.

También se conservan reglas de crecimiento de cola y estimación de agotamiento
de disco; esas reglas heredadas todavía usan consultas globales. La retención
de métricas es de siete días, con purga horaria.

## Compilar y ejecutar como JAR

```bash
./mvnw -DskipTests package
java -jar target/consumereventos-0.0.1-SNAPSHOT.jar
```

Detén la instancia anterior antes de arrancar el JAR en el mismo puerto.
Ejecuta desde la raíz para conservar las rutas relativas de H2 y la configuración.

## Pruebas aisladas

Este comando usa una base en memoria, no inicia el listener Kafka y desactiva
Slack para no enviar notificaciones reales:

```bash
./mvnw \
  '-Dspring.datasource.url=jdbc:h2:mem:vigia-test;DB_CLOSE_DELAY=-1' \
  -Dspring.kafka.listener.auto-startup=false \
  -Dapp.slack.webhook-url= \
  -Dapp.ia.habilitada=false \
  test
```

## Problemas frecuentes

| Síntoma | Revisar |
|---|---|
| Puerto ocupado | Detén la instancia anterior o cambia `SERVER_PORT` y `DASHBOARD_URL`. |
| Kafka no conecta | Broker activo, puerto Docker publicado y advertised listeners accesibles. |
| Dashboard sin servicios | Eventos `up` con `job`, datos en la H2 correcta y grupo consumidor. |
| No aparecen eventos antiguos | `earliest` solo aplica si el grupo no tiene offset guardado; no fuerza una relectura. |
| No alerta al apagar temperatura | Deben llegar tres `up=0` actuales; verifica el ciclo de análisis y silencio de dos minutos. |
| Slack no llega | Webhook válido, configuración cargada, salida HTTPS y logs de confirmación/error. |
| Sigue en “Modo reglas” | `IA_HABILITADA=true`, credenciales y reinicio; el LLM solo se consulta con score >= 30. |
| Falló OpenAI | Clave, acceso al modelo, cuota y red; las reglas continúan funcionando. |
| Parece otra base de datos | Revisa la carpeta de ejecución y `SPRING_DATASOURCE_URL`; no borres `data/` para resolverlo. |

Este es un MVP local. No expongas el dashboard ni el endpoint que ejecuta análisis
a Internet sin agregar autenticación, autorización y controles de operación.

## Equipo

Zomeli Villanueva · Betzabe Salas · Carlos Caso · Ninisbeth Segovia
