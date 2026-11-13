package com.hackatonbcp.consumereventos;

import com.hackatonbcp.consumereventos.Pronostico.Senal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PronosticoService {

  private static final Duration CACHE_IA = Duration.ofMinutes(2);

  record Signals(
      double up, double heap, double heapPorMin, double cpu, double latenciaMs, double pool, double discoLibre) {}

  record IaRespuesta(String causa, List<String> acciones) {}

  private record Cacheado(Instant momento, int probabilidad, IaRespuesta respuesta) {}

  private final MetricaRepository metricas;
  private final ChatClient chat;
  private final Map<String, Cacheado> cacheIa = new ConcurrentHashMap<>();

  public PronosticoService(
      MetricaRepository metricas,
      ObjectProvider<ChatClient.Builder> builder,
      @Value("${app.ia.habilitada:false}") boolean iaHabilitada) {
    this.metricas = metricas;
    ChatClient.Builder b = iaHabilitada ? builder.getIfAvailable() : null;
    this.chat = b == null ? null : b.build();
  }

  public boolean iaActiva() {
    return chat != null;
  }

  public List<Pronostico> pronosticos() {
    return serviciosReales().stream().map(this::pronosticar).sorted((a, b) -> b.probabilidad() - a.probabilidad()).toList();
  }

  public Optional<Pronostico> pronostico(String servicio) {
    return pronosticos().stream().filter(p -> p.servicio().equals(servicio)).findFirst();
  }

  private Set<String> serviciosReales() {
    Set<String> jobs = new LinkedHashSet<>();
    for (Metrica m : metricas.findByNombreOrderByMomentoDesc("up", PageRequest.of(0, 50))) {
      String job = etiqueta(m.getLabels(), "job");
      if (job != null) {
        jobs.add(job);
      }
    }
    return jobs;
  }

  private Pronostico pronosticar(String servicio) {
    Signals s = senales(servicio);
    int prob = probabilidad(s);
    String nivel = prob >= 70 ? "tormenta" : prob >= 30 ? "nublado" : "despejado";

    List<String> acciones = accionesReglas(s);
    String causa = causaReglas(s);
    boolean ia = false;
    if (chat != null && prob >= 30) {
      IaRespuesta r = consultarIa(servicio, s, prob);
      if (r != null && r.causa() != null && r.acciones() != null && !r.acciones().isEmpty()) {
        causa = r.causa();
        acciones = r.acciones();
        ia = true;
      }
    }

    String estado;
    if (nivel.equals("tormenta")) {
      estado = "Tormenta · caída en ~5 min";
    } else if (nivel.equals("nublado")) {
      estado = "Nublado · posibles problemas en ~25 min";
    } else {
      estado = "Despejado · sin riesgo previsto";
    }
    String detalle = prob >= 30 ? causa : "Todas sus señales dentro de lo normal";

    List<Integer> proy = new ArrayList<>();
    for (int i = 0; i <= 5; i++) {
      proy.add(prob >= 30 ? Math.min(99, prob - 18 + i * Math.max(2, (99 - prob + 18) / 6)) : prob);
    }
    if (prob >= 30) {
      proy.set(0, Math.min(proy.get(0), prob));
    }

    List<Senal> senales = new ArrayList<>();
    senales.add(new Senal("Disponibilidad (up)", s.up() >= 1 ? "arriba" : s.up() > 0 ? "intermitente" : "caído", "arriba", s.up() >= 1 ? "ok" : "alto"));
    senales.add(new Senal("Memoria heap (tras GC)", fmt(s.heap()) + " %", "< 70 %", s.heap() >= 85 ? "alto" : s.heap() >= 70 ? "medio" : "ok"));
    senales.add(new Senal("Tendencia heap", String.format("%+.2f %%/min", s.heapPorMin()), "≈ 0", s.heapPorMin() >= 2 ? "alto" : s.heapPorMin() >= 0.5 ? "medio" : "ok"));
    senales.add(new Senal("CPU del proceso", fmt(s.cpu()) + " %", "< 80 %", s.cpu() >= 90 ? "alto" : s.cpu() >= 80 ? "medio" : "ok"));
    senales.add(new Senal("Tiempo de respuesta", fmt(s.latenciaMs()) + " ms", "< 300 ms", s.latenciaMs() > 1000 ? "alto" : s.latenciaMs() > 300 ? "medio" : "ok"));
    senales.add(new Senal("Hilos / pool", fmt(s.pool() * 100) + " %", "< 80 %", s.pool() >= 1 ? "alto" : s.pool() >= 0.8 ? "medio" : "ok"));
    senales.add(new Senal("Disco libre", fmt(s.discoLibre()) + " %", "> 15 %", s.discoLibre() < 5 ? "alto" : s.discoLibre() < 15 ? "medio" : "ok"));
    return new Pronostico(servicio, prob, nivel, estado, detalle, causa, acciones, proy, senales, ia);
  }

  private IaRespuesta consultarIa(String servicio, Signals s, int prob) {
    Cacheado c = cacheIa.get(servicio);
    if (c != null && c.probabilidad() == prob && c.momento().isAfter(Instant.now().minus(CACHE_IA))) {
      return c.respuesta();
    }
    try {
      IaRespuesta r =
          chat.prompt()
              .system(
                  "Eres un SRE. Con las señales de un microservicio explicas en español, en una frase, la causa probable "
                      + "de una caída inminente y propones entre 2 y 4 acciones preventivas concretas y cortas.")
              .user(
                  "Servicio: %s. Probabilidad de caída calculada: %d%%. Señales: disponibilidad=%.2f, heap=%.0f%% (%+.2f%%/min), "
                          .formatted(servicio, prob, s.up(), s.heap(), s.heapPorMin())
                      + "cpu=%.0f%%, tiempo de respuesta=%.0fms, uso de pool=%.0f%%, disco libre=%.1f%%."
                          .formatted(s.cpu(), s.latenciaMs(), s.pool() * 100, s.discoLibre()))
              .call()
              .entity(IaRespuesta.class);
      cacheIa.put(servicio, new Cacheado(Instant.now(), prob, r));
      return r;
    } catch (RuntimeException e) {
      log.warn("Spring AI no disponible, se usan reglas: {}", e.getMessage());
      return null;
    }
  }

  private int probabilidad(Signals s) {
    double p = 5;
    if (s.up() == 0) p += 85;
    else if (s.up() < 1) p += 45;
    if (s.discoLibre() < 5) p += 60;
    else if (s.discoLibre() < 15) p += 30;
    if (s.pool() >= 1) p += 35;
    else if (s.pool() >= 0.8) p += 20;
    if (s.latenciaMs() > 1000) p += 25;
    else if (s.latenciaMs() > 300) p += 12;
    if (s.heap() >= 85) p += 60;
    else if (s.heap() >= 70) p += 25;
    if (s.heapPorMin() >= 2) p += 20;
    else if (s.heapPorMin() >= 0.5) p += 8;
    if (s.cpu() >= 90) p += 20;
    else if (s.cpu() >= 80) p += 10;
    return (int) Math.min(97, Math.round(p));
  }

  private String causaReglas(Signals s) {
    if (s.up() == 0) return "El servicio no responde: las últimas muestras reportan up=0.";
    if (s.heap() >= 70) return "Fuga de memoria. El heap sube sostenidamente y el recolector ya no logra liberarlo.";
    if (s.pool() >= 0.8 && s.latenciaMs() > 300) return "Pool agotado: solicitudes esperando conexión o hilo disponible.";
    if (s.cpu() >= 80) return "CPU del proceso saturada por exceso de carga.";
    if (s.latenciaMs() > 300) return "Latencia fuera de rango por saturación o dependencias lentas.";
    if (s.discoLibre() < 15) return "Poco espacio libre en disco.";
    if (s.up() < 1) return "El servicio responde de forma intermitente.";
    return "Sin anomalías relevantes.";
  }

  private List<String> accionesReglas(Signals s) {
    if (s.up() < 1) {
      return List.of("Revisar el proceso, los logs y la conectividad.", "Reiniciar el servicio si es necesario.", "Verificar el último despliegue.");
    }
    if (s.heap() >= 70) {
      return List.of("Tomar un heap dump antes de reiniciar.", "Reiniciar una réplica y subir el heap a 1 GB.", "Revisar cachés sin límite introducidas en el último despliegue.");
    }
    if (s.pool() >= 0.8) {
      return List.of("Aumentar el pool de hilos/conexiones o escalar réplicas.", "Revisar consultas lentas a la BD.", "Limitar la entrada de trabajo.");
    }
    if (s.cpu() >= 80) {
      return List.of("Escalar réplicas para repartir la carga.", "Perfilar los hilos más costosos.", "Considerar rollback del último despliegue.");
    }
    if (s.discoLibre() < 15) {
      return List.of("Limpiar logs y archivos temporales.", "Programar rotación de logs.", "Planificar ampliación de almacenamiento.");
    }
    return List.of("Mantener monitoreo habitual.");
  }

  private Signals senales(String job) {
    List<Metrica> ups = recientes("up", job, 3);
    double up = ups.isEmpty() ? 1 : ups.stream().mapToDouble(Metrica::getValor).average().orElse(1);
    double libre = ultimo("disk_free_bytes", job, -1);
    double total = ultimo("disk_total_bytes", job, -1);
    double discoLibre = libre >= 0 && total > 0 ? libre / total * 100 : 100;
    double pool = ultimo("executor_pool_core_threads", job, 0) > 0
        ? ultimo("executor_active_threads", job, 0) / ultimo("executor_pool_core_threads", job, 1)
        : 0;
    double latencia = ultimo("scrape_duration_seconds", job, 0) * 1000;
    double cpu = ultimo("process_cpu_usage", job, 0) * 100;

    List<Metrica> heap =
        metricas.findByNombreAndMomentoAfterOrderByMomentoAsc("jvm_memory_usage_after_gc", Instant.now().minus(Duration.ofMinutes(30))).stream()
            .filter(m -> job.equals(etiqueta(m.getLabels(), "job")))
            .toList();
    double heapPct = heap.isEmpty() ? ultimo("jvm_memory_usage_after_gc", job, 0) * 100 : heap.get(heap.size() - 1).getValor() * 100;
    return new Signals(up, heapPct, pendientePorMinuto(heap) * 100, cpu, latencia, pool, discoLibre);
  }

  private double pendientePorMinuto(List<Metrica> datos) {
    if (datos.size() < 5) return 0;
    long t0 = datos.get(0).getMomento().getEpochSecond();
    double n = datos.size(), sx = 0, sy = 0, sxy = 0, sxx = 0;
    for (Metrica m : datos) {
      double x = (m.getMomento().getEpochSecond() - t0) / 60.0;
      sx += x;
      sy += m.getValor();
      sxy += x * m.getValor();
      sxx += x * x;
    }
    double d = n * sxx - sx * sx;
    return d == 0 ? 0 : (n * sxy - sx * sy) / d;
  }

  private List<Metrica> recientes(String nombre, String job, int limite) {
    return metricas.findByNombreOrderByMomentoDesc(nombre, PageRequest.of(0, 100)).stream()
        .filter(m -> job.equals(etiqueta(m.getLabels(), "job")))
        .limit(limite)
        .toList();
  }

  private double ultimo(String nombre, String job, double defecto) {
    return recientes(nombre, job, 1).stream().findFirst().map(Metrica::getValor).orElse(defecto);
  }

  private static String etiqueta(String labels, String clave) {
    if (labels == null) return null;
    for (String par : labels.split(",")) {
      if (par.startsWith(clave + "=")) return par.substring(clave.length() + 1);
    }
    return null;
  }

  private static String fmt(double v) {
    return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v);
  }
}
