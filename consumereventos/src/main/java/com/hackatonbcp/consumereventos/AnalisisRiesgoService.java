package com.hackatonbcp.consumereventos;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalisisRiesgoService {

  private static final Duration VENTANA_TENDENCIA = Duration.ofHours(24);
  private static final Duration RETENCION = Duration.ofDays(7);
  private static final Duration SILENCIO = Duration.ofMinutes(2);
  private static final int MUESTRAS_CAIDA = 3;

  private final MetricaRepository metricas;
  private final RecomendacionRepository recomendaciones;
  private final SlackClient slack;
  private final AnomaliasMetricasService anomalias;

  @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
  public synchronized void analizar() {
    evaluarDisponibilidad();
    evaluarAnomalias();
    evaluarDisco();
    evaluarHilos();
    notificarPendientes();
  }

  private void evaluarAnomalias() {
    for (Recomendacion alerta : anomalias.detectar(Instant.now())) {
      Optional<Recomendacion> anterior = recomendaciones
          .findFirstByTipoAndOrigenOrderByCreadaDesc(alerta.getTipo(), alerta.getOrigen());
      if (anterior.isPresent()
          && anterior.get().getCreada().isAfter(Instant.now().minus(SILENCIO))
          && prioridad(alerta.getSeveridad()) <= prioridad(anterior.get().getSeveridad())) {
        continue;
      }
      guardar(alerta.getTipo(), alerta.getSeveridad(), alerta.getRiesgo(),
          alerta.getAccion(), alerta.getOrigen());
    }
  }

  private int prioridad(String severidad) {
    return switch (severidad) {
      case "CRITICA" -> 3;
      case "ALTA" -> 2;
      case "MEDIA" -> 1;
      default -> 0;
    };
  }

  @Scheduled(cron = "0 0 * * * *")
  @Transactional
  public void purgar() {
    long borradas = metricas.deleteByMomentoBefore(Instant.now().minus(RETENCION));
    log.info("Metricas antiguas purgadas: {}", borradas);
  }

  private void evaluarDisponibilidad() {
    for (String origen : metricas.buscarTargetsDisponibilidad()) {
      List<Metrica> ultimas = metricas.findByNombreAndLabelsOrderByMomentoDesc(
          "up", origen, PageRequest.of(0, MUESTRAS_CAIDA));
      if (ultimas.isEmpty()
          || ultimas.get(0).getMomento().isBefore(Instant.now().minus(Duration.ofMinutes(2)))) {
        continue;
      }
      if (ultimas.get(0).getValor() == 1) {
        evaluarRecuperacion(origen, ultimas.get(0));
        continue;
      }
      if (ultimas.size() != MUESTRAS_CAIDA
          || ultimas.stream().anyMatch(m -> m.getValor() != 0)) {
        continue;
      }
      Optional<Recomendacion> anterior =
          recomendaciones.findFirstByTipoAndOrigenOrderByCreadaDesc("SERVICIO_CAIDO", origen);
      Instant recuperacion = metricas
          .findFirstByNombreAndLabelsAndValorGreaterThanOrderByMomentoDesc("up", origen, 0)
          .map(Metrica::getMomento).orElse(Instant.MIN);
      if (anterior.isPresent()
          && !recuperacion.isAfter(anterior.get().getCreada())
          && anterior.get().getCreada().isAfter(Instant.now().minus(SILENCIO))) {
        continue;
      }
      guardar(
          "SERVICIO_CAIDO",
          "CRITICA",
          "El servicio [" + origen + "] no responde en las ultimas "
              + MUESTRAS_CAIDA + " muestras (up=0).",
          "Revisar el proceso, los logs y la conectividad del servicio; reiniciarlo si es necesario.",
          origen);
    }
  }

  private void evaluarRecuperacion(String origen, Metrica actual) {
    Optional<Metrica> ultimaCaida =
        metricas.findFirstByNombreAndLabelsAndValorOrderByMomentoDesc("up", origen, 0);
    if (ultimaCaida.isEmpty()
        || !actual.getMomento().isAfter(ultimaCaida.get().getMomento())) {
      return;
    }
    Optional<Recomendacion> anterior =
        recomendaciones.findFirstByTipoAndOrigenOrderByCreadaDesc("SERVICIO_RECUPERADO", origen);
    if (anterior.isPresent()
        && !anterior.get().getCreada().isBefore(ultimaCaida.get().getMomento())) {
      return;
    }
    guardar(
        "SERVICIO_RECUPERADO",
        "INFO",
        "El servicio [" + origen + "] vuelve a estar disponible (up=1).",
        "Confirmar su funcionamiento y mantener el monitoreo.",
        origen);
  }

  private void evaluarDisco() {
    Optional<Double> libre = ultimoValor("disk_free_bytes");
    Optional<Double> total = ultimoValor("disk_total_bytes");
    if (libre.isEmpty() || total.isEmpty() || total.get() <= 0) {
      return;
    }
    List<Metrica> historico =
        metricas.findByNombreAndMomentoAfterOrderByMomentoAsc(
            "disk_free_bytes", Instant.now().minus(VENTANA_TENDENCIA));
    double pendientePorSegundo = pendiente(historico);
    if (pendientePorSegundo < 0) {
      double diasRestantes = libre.get() / -pendientePorSegundo / 86_400;
      if (diasRestantes < 7) {
        registrar(
            "DISCO_SE_LLENA",
            diasRestantes < 2 ? "ALTA" : "MEDIA",
            String.format(
                "Con la tendencia de las ultimas 24h el disco se llenaria en ~%.1f dias.", diasRestantes),
            "Identificar que consume espacio, programar limpieza o rotacion de logs y planificar ampliacion.");
      }
    }
  }

  private void evaluarHilos() {
    List<Metrica> cola =
        metricas.findByNombreAndMomentoAfterOrderByMomentoAsc(
            "executor_queued_tasks", Instant.now().minus(Duration.ofMinutes(15)));
    if (cola.size() >= 5 && pendiente(cola) > 0 && cola.get(cola.size() - 1).getValor() > 0) {
      registrar(
          "COLA_CRECIENTE",
          "MEDIA",
          "La cola de tareas pendientes crece de forma sostenida en los ultimos 15 minutos.",
          "Subir la capacidad de procesamiento o limitar la entrada de trabajo.");
    }
  }

  private Optional<Double> ultimoValor(String nombre) {
    return metricas.findByNombreOrderByMomentoDesc(nombre, PageRequest.of(0, 1)).stream()
        .findFirst()
        .map(Metrica::getValor);
  }

  // Regresion lineal simple: unidades de valor por segundo
  private double pendiente(List<Metrica> datos) {
    if (datos.size() < 5) {
      return 0;
    }
    long t0 = datos.get(0).getMomento().getEpochSecond();
    double n = datos.size();
    double sx = 0, sy = 0, sxy = 0, sxx = 0;
    for (Metrica m : datos) {
      double x = m.getMomento().getEpochSecond() - t0;
      sx += x;
      sy += m.getValor();
      sxy += x * m.getValor();
      sxx += x * x;
    }
    double denominador = n * sxx - sx * sx;
    return denominador == 0 ? 0 : (n * sxy - sx * sy) / denominador;
  }

  private void registrar(String tipo, String severidad, String riesgo, String accion) {
    if (recomendaciones.existsByTipoAndCreadaAfter(tipo, Instant.now().minus(SILENCIO))) {
      return;
    }
    guardar(tipo, severidad, riesgo, accion, null);
  }

  private void guardar(String tipo, String severidad, String riesgo, String accion, String origen) {
    Recomendacion r = new Recomendacion();
    r.setCreada(Instant.now());
    r.setTipo(tipo);
    r.setSeveridad(severidad);
    r.setRiesgo(riesgo);
    r.setAccion(accion);
    r.setOrigen(origen);
    r.setPendienteSlack(true);
    recomendaciones.save(r);
    log.warn("Recomendacion [{}] {}: {}", severidad, tipo, riesgo);
  }

  private void notificarPendientes() {
    for (Recomendacion r : recomendaciones.findTop50ByPendienteSlackTrueOrderByCreadaAsc()) {
      if (slack.notificar(r)) {
        r.setPendienteSlack(false);
        recomendaciones.save(r);
      }
    }
  }
}
