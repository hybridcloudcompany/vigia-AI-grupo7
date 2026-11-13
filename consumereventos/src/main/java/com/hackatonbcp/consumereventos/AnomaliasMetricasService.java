package com.hackatonbcp.consumereventos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoublePredicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnomaliasMetricasService {

  private static final List<String> NOMBRES = List.of(
      "process_cpu_usage", "jvm_memory_usage_after_gc", "scrape_duration_seconds",
      "executor_active_threads", "executor_pool_core_threads", "disk_free_bytes", "disk_total_bytes");

  record Punto(Instant momento, double valor) {}

  record Regla(String tipo, String nombre, String unidad, double incrementoMinimo,
               double pisoHistorico, boolean descendente, DoublePredicate umbral,
               DoublePredicate critico, String accion) {}

  private final MetricaRepository metricas;

  public List<Recomendacion> detectar(Instant ahora) {
    List<Metrica> datos = metricas.findByNombreInAndMomentoAfterOrderByMomentoAsc(
        NOMBRES, ahora.minus(Duration.ofMinutes(2)));
    Map<String, List<Metrica>> series = datos.stream()
        .filter(m -> Double.isFinite(m.getValor()))
        .collect(Collectors.groupingBy(m -> m.getNombre() + "|" + etiquetas(m.getLabels())));
    List<Recomendacion> alertas = new ArrayList<>();
    for (List<Metrica> serie : series.values()) {
      Metrica primera = serie.get(0);
      Map<String, String> labels = etiquetas(primera.getLabels());
      if (!labels.containsKey("job") || !labels.containsKey("instance")) {
        continue;
      }
      String origen = labels.toString();
      switch (primera.getNombre()) {
        case "process_cpu_usage" -> evaluar(alertas, convertir(serie, 100), ahora, origen,
            new Regla("CPU_ANOMALA", "CPU del proceso", "%", 15, 20, false,
                v -> v >= 80, v -> v >= 90,
                "Perfilar los hilos de mayor consumo, revisar carga y escalar replicas."));
        case "jvm_memory_usage_after_gc" -> {
          List<Punto> heap = convertir(serie, 100);
          evaluar(alertas, heap, ahora, origen,
              new Regla("HEAP_ANOMALO", "Heap tras GC", "%", 10, 20, false,
                  v -> v >= 70, v -> v >= 85,
                  "Capturar un heap dump, revisar retencion de objetos y caches antes de reiniciar."));
          evaluar(alertas, tendencias(heap), ahora, origen,
              new Regla("TENDENCIA_HEAP_ANOMALA", "Tendencia heap", "pp/min", 0.3, 0.2, false,
                  v -> v >= 0.5, v -> v >= 2,
                  "Investigar crecimiento sostenido de memoria y comprobar si el GC libera objetos."));
        }
        case "scrape_duration_seconds" -> evaluar(alertas, convertir(serie, 1000), ahora, origen,
            new Regla("DEGRADACION_RESPUESTA", "Duracion del scrape", "ms", 50, 50, false,
                v -> v > 300, v -> v > 1000,
                "Revisar el endpoint de metricas, CPU y conectividad; el scrape no mide latencia HTTP p99."));
        case "executor_active_threads" -> evaluar(alertas,
            cocientes(serie, series.get("executor_pool_core_threads|" + labels)), ahora, origen,
            new Regla("HILOS_SATURADOS", "Hilos / pool base", "%", 20, 20, false,
                v -> v >= 80, v -> v >= 100,
                "Revisar tareas bloqueadas, ajustar el pool base o escalar capacidad."));
        case "disk_free_bytes" -> evaluar(alertas,
            cocientes(serie, series.get("disk_total_bytes|" + labels)), ahora, origen,
            new Regla("DISCO_BAJO", "Disco libre", "%", 3, 0, true,
                v -> v < 15, v -> v < 5,
                "Revisar consumo de espacio, rotar logs y ampliar almacenamiento si es necesario."));
        default -> { }
      }
    }
    return alertas;
  }

  private void evaluar(List<Recomendacion> alertas, List<Punto> puntos, Instant ahora,
                       String origen, Regla regla) {
    if (puntos.size() < 3) {
      return;
    }
    List<Punto> recientes = puntos.subList(puntos.size() - 3, puntos.size());
    Punto ultimo = recientes.get(2);
    if (ultimo.momento().isBefore(ahora.minusSeconds(120))
        || ultimo.momento().isAfter(ahora)
        || Duration.between(recientes.get(0).momento(), ultimo.momento()).getSeconds() < 30
        || Duration.between(recientes.get(0).momento(), recientes.get(1).momento()).getSeconds() > 60
        || Duration.between(recientes.get(1).momento(), ultimo.momento()).getSeconds() > 60) {
      return;
    }
    List<Punto> base = puntos.stream()
        .filter(p -> p.momento().isBefore(recientes.get(0).momento())).toList();
    double media = base.stream().mapToDouble(Punto::valor).average().orElse(0);
    double desviacion = Math.sqrt(base.stream()
        .mapToDouble(p -> Math.pow(p.valor() - media, 2)).average().orElse(0));
    double salto = Math.max(regla.incrementoMinimo(), 3 * desviacion);
    if (!regla.descendente()) {
      salto = Math.max(salto, Math.abs(media) * 0.5);
    }
    final double diferencia = salto;
    boolean fueraDeUmbral = recientes.stream().allMatch(p -> regla.umbral().test(p.valor()));
    boolean anomaliaHistorica = base.size() >= 5
        && Duration.between(base.get(0).momento(), base.get(base.size() - 1).momento()).getSeconds() >= 60
        && recientes.stream().allMatch(p -> regla.descendente()
            ? media - p.valor() >= diferencia
            : p.valor() >= regla.pisoHistorico() && p.valor() - media >= diferencia);
    if (!fueraDeUmbral && !anomaliaHistorica) {
      return;
    }
    Recomendacion alerta = new Recomendacion();
    alerta.setTipo(regla.tipo());
    alerta.setOrigen(origen);
    boolean critico = recientes.stream().allMatch(p -> regla.critico().test(p.valor()));
    alerta.setSeveridad("DISCO_BAJO".equals(regla.tipo())
        ? (critico ? "CRITICA" : "ALTA") : (critico ? "ALTA" : "MEDIA"));
    alerta.setRiesgo(String.format(
        "%s [%s]: %.2f %s. %s en 3 muestras consecutivas.%s",
        regla.nombre(), origen, ultimo.valor(), regla.unidad(),
        fueraDeUmbral ? "Umbral de riesgo superado" : "Variacion anomala frente al historico",
        base.size() >= 5 ? String.format(" Media previa: %.2f %s.", media, regla.unidad()) : ""));
    alerta.setAccion(regla.accion());
    alertas.add(alerta);
  }

  private List<Punto> convertir(List<Metrica> datos, double factor) {
    Map<Instant, Double> unicos = new TreeMap<>();
    datos.forEach(m -> unicos.put(m.getMomento(), m.getValor() * factor));
    return unicos.entrySet().stream().map(e -> new Punto(e.getKey(), e.getValue())).toList();
  }

  private List<Punto> cocientes(List<Metrica> numeradores, List<Metrica> denominadores) {
    if (denominadores == null) {
      return List.of();
    }
    Map<Instant, Double> porMomento = new HashMap<>();
    denominadores.forEach(m -> porMomento.put(m.getMomento(), m.getValor()));
    Map<Instant, Double> valores = new TreeMap<>();
    for (Metrica m : numeradores) {
      Double total = porMomento.get(m.getMomento());
      if (total != null && total > 0 && m.getValor() >= 0) {
        valores.put(m.getMomento(), m.getValor() / total * 100);
      }
    }
    return valores.entrySet().stream().map(e -> new Punto(e.getKey(), e.getValue())).toList();
  }

  private List<Punto> tendencias(List<Punto> heap) {
    List<Punto> resultado = new ArrayList<>();
    for (int i = 4; i < heap.size(); i++) {
      List<Punto> ventana = heap.subList(i - 4, i + 1);
      long segundos = Duration.between(ventana.get(0).momento(), ventana.get(4).momento()).getSeconds();
      if (segundos < 60 || segundos > 120) {
        continue;
      }
      double sx = 0, sy = 0, sxx = 0, sxy = 0;
      for (Punto p : ventana) {
        double x = Duration.between(ventana.get(0).momento(), p.momento()).toMillis() / 60_000.0;
        sx += x;
        sy += p.valor();
        sxx += x * x;
        sxy += x * p.valor();
      }
      double d = 5 * sxx - sx * sx;
      if (d > 0) {
        resultado.add(new Punto(ventana.get(4).momento(), (5 * sxy - sx * sy) / d));
      }
    }
    return resultado;
  }

  private Map<String, String> etiquetas(String texto) {
    if (texto == null || texto.isBlank()) {
      return Map.of();
    }
    return Arrays.stream(texto.split(",")).map(s -> s.split("=", 2))
        .filter(par -> par.length == 2)
        .collect(Collectors.toMap(par -> par[0], par -> par[1], (a, b) -> b, TreeMap::new));
  }
}
