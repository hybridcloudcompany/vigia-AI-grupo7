# Temperature Project

Servicio REST con Spring Boot para convertir temperaturas entre Celsius y Fahrenheit, obtener una lectura de ejemplo y consultar metricas con Actuator y Prometheus.

## Requisitos

- JDK 17 y `JAVA_HOME` configurado.
- Maven 3.6.3 o superior, o el Maven Wrapper incluido.
- Puerto `8080` disponible.
- Acceso a Internet para descargar las dependencias en la primera ejecucion.

## Levantar el proyecto

Desde la carpeta del proyecto, donde se encuentra `pom.xml`:

```bash
cd temperature-project
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-XX:MaxRAMPercentage=75.0 -XX:ActiveProcessorCount=2"
```

Si ya estas dentro de `temperature-project`, omite el comando `cd`.

Los argumentos de la JVM configuran:

- `MaxRAMPercentage=75.0`: calcula el limite maximo del heap utilizando el 75 % de la memoria disponible para la JVM. No limita la memoria total del proceso.
- `ActiveProcessorCount=2`: hace que la JVM considere dos procesadores disponibles; no impone un limite de CPU del sistema operativo.

Si no tienes Maven instalado, usa el wrapper:

```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-XX:MaxRAMPercentage=75.0 -XX:ActiveProcessorCount=2"
```

En Windows:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.jvmArguments=-XX:MaxRAMPercentage=75.0 -XX:ActiveProcessorCount=2'
```

La aplicacion queda disponible en `http://localhost:8080`. Para detenerla, presiona `Ctrl+C`.

## Probar la aplicacion

Verificar el estado:

```bash
curl http://localhost:8080/actuator/health
```

Convertir Celsius a Fahrenheit:

```bash
curl "http://localhost:8080/api/v1/convert/celsius-to-fahrenheit?value=25"
```

Convertir Fahrenheit a Celsius:

```bash
curl "http://localhost:8080/api/v1/convert/fahrenheit-to-celsius?value=77"
```

Obtener una temperatura de ejemplo (no corresponde a un sensor real):

```bash
curl "http://localhost:8080/api/v1/temperature?unit=CELSIUS&location=Lima"
```

## Monitoreo

| Endpoint | Descripcion |
| --- | --- |
| `/actuator/health` | Estado del servicio |
| `/actuator/info` | Informacion del servicio |
| `/actuator/metrics` | Metricas disponibles en Actuator |
| `/actuator/prometheus` | Metricas en formato Prometheus |
| `/metrics` | Endpoint alternativo de metricas Prometheus |
| `/monitoring/summary?window=5m` | Resumen de memoria, GC, latencia, errores e hilos |

El resumen admite las ventanas `1m`, `5m`, `15m` y `1h`; por defecto utiliza `5m`.

```bash
curl http://localhost:8080/actuator/prometheus
curl "http://localhost:8080/monitoring/summary?window=5m"
```

## Compilar y ejecutar el JAR

```bash
mvn clean package
java -XX:MaxRAMPercentage=75.0 -XX:ActiveProcessorCount=2 -jar target/monitored-service-0.0.1-SNAPSHOT.jar
```

Para ejecutar solo las pruebas:

```bash
mvn test
```

Puedes sustituir `mvn` por `./mvnw` en estos comandos.

## Configuracion

La configuracion esta en `src/main/resources/application.properties`. El nombre del servicio es `monitored-service` y el puerto predeterminado es `8080`.

Para utilizar otro puerto conservando los argumentos de la JVM:

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-XX:MaxRAMPercentage=75.0 -XX:ActiveProcessorCount=2" -Dspring-boot.run.arguments="--server.port=8081"
```

El contrato de la API esta en `src/main/resources/openapi.yaml`. Maven genera las interfaces y modelos durante la compilacion.

Los endpoints de monitoreo estan expuestos para uso local; restringe su acceso antes de desplegar en un entorno publico.
