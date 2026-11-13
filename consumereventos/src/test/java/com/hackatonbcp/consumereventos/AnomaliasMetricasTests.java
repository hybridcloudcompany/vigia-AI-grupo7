package com.hackatonbcp.consumereventos;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AnomaliasMetricasTests {

  private static final Instant AHORA = Instant.parse("2026-11-13T18:00:00Z");
  private static final String LABELS = "instance=localhost:8080,job=temperatura";
  private final MetricaRepository metricas = mock(MetricaRepository.class);
  private final AnomaliasMetricasService detector = new AnomaliasMetricasService(metricas);

  @ParameterizedTest
  @CsvSource({
      "process_cpu_usage,0.8,CPU_ANOMALA",
      "jvm_memory_usage_after_gc,0.7,HEAP_ANOMALO",
      "scrape_duration_seconds,0.301,DEGRADACION_RESPUESTA"
  })
  void notificaTresMuestrasQueSuperanElUmbral(String nombre, double valor, String tipo) {
    List<Recomendacion> alertas = detectar(serie(nombre, valor, 3));
    assertEquals(1, alertas.size());
    assertEquals(tipo, alertas.get(0).getTipo());
    assertTrue(alertas.get(0).getOrigen().contains("job=temperatura"));
  }

  @ParameterizedTest
  @CsvSource({
      "process_cpu_usage,0.1,0.5,CPU_ANOMALA",
      "jvm_memory_usage_after_gc,0.1,0.4,HEAP_ANOMALO",
      "scrape_duration_seconds,0.01,0.1,DEGRADACION_RESPUESTA"
  })
  void detectaSaltoSostenidoAunqueNoSupereElUmbral(
      String nombre, double base, double actual, String tipo) {
    List<Metrica> datos = serie(nombre, base, 5);
    datos.forEach(m -> m.setMomento(m.getMomento().minusSeconds(45)));
    datos.addAll(serie(nombre, actual, 3));
    List<Recomendacion> alertas = detectar(datos);
    Recomendacion alerta = alertas.stream().filter(a -> tipo.equals(a.getTipo())).findFirst().orElseThrow();
    assertTrue(alerta.getRiesgo().contains("Variacion anomala"));
  }

  @Test
  void noNotificaUnPicoAisladoNiValoresNormales() {
    List<Metrica> datos = serie("process_cpu_usage", 0.1, 24);
    datos.get(datos.size() - 1).setValor(0.99);
    assertTrue(detectar(datos).isEmpty());
    assertTrue(detectar(serie("process_cpu_usage", 0.2, 24)).isEmpty());
  }

  @Test
  void noUsaHistoriaInsuficienteParaUnSaltoBajoElUmbral() {
    List<Metrica> datos = serie("process_cpu_usage", 0.1, 4);
    datos.forEach(m -> m.setMomento(m.getMomento().minusSeconds(45)));
    datos.addAll(serie("process_cpu_usage", 0.5, 3));
    assertTrue(detectar(datos).isEmpty());
  }

  @Test
  void noAlertaConMuestrasViejasODuplicadas() {
    List<Metrica> datos = serie("process_cpu_usage", 0.99, 3);
    datos.forEach(m -> m.setMomento(m.getMomento().minusSeconds(300)));
    assertTrue(detectar(datos).isEmpty());
    Metrica unica = serie("process_cpu_usage", 0.99, 1).get(0);
    assertTrue(detectar(List.of(unica, unica, unica)).isEmpty());
  }

  @Test
  void noMezclaServiciosNiInstancias() {
    List<Metrica> datos = serie("process_cpu_usage", 0.99, 3);
    datos.get(0).setLabels("instance=localhost:8081,job=temperatura");
    datos.get(1).setLabels("instance=localhost:8080,job=otro");
    assertTrue(detectar(datos).isEmpty());
  }

  @Test
  void calculaHilosYDiscoConSuDenominadorExacto() {
    List<Metrica> datos = serie("executor_active_threads", 8, 3);
    datos.addAll(serie("executor_pool_core_threads", 10, 3));
    datos.addAll(serie("disk_free_bytes", 10, 3));
    datos.addAll(serie("disk_total_bytes", 100, 3));
    assertEquals(List.of("DISCO_BAJO", "HILOS_SATURADOS"),
        detectar(datos).stream().map(Recomendacion::getTipo).sorted().toList());
  }

  @Test
  void notificaCriticidadDeDiscoSinEsperarHistoria() {
    List<Metrica> datos = serie("disk_free_bytes", 4, 3);
    datos.addAll(serie("disk_total_bytes", 100, 3));
    assertEquals("CRITICA", detectar(datos).get(0).getSeveridad());
  }

  @Test
  void respetaLimitesNormalesDeScrapeYDisco() {
    List<Metrica> datos = serie("scrape_duration_seconds", 0.3, 3);
    datos.addAll(serie("disk_free_bytes", 15, 3));
    datos.addAll(serie("disk_total_bytes", 100, 3));
    assertTrue(detectar(datos).isEmpty());
  }

  @Test
  void noDividePorCeroNiCruzaPoolsOPaths() {
    List<Metrica> datos = serie("executor_active_threads", 8, 3);
    datos.addAll(serie("executor_pool_core_threads", 0, 3));
    datos.addAll(serie("disk_free_bytes", 10, 3));
    List<Metrica> totales = serie("disk_total_bytes", 100, 3);
    totales.forEach(m -> m.setLabels(LABELS + ",path=/otro"));
    datos.addAll(totales);
    assertTrue(detectar(datos).isEmpty());
  }

  @Test
  void detectaDisminucionDeDiscoFrenteAlHistorico() {
    List<Metrica> datos = serie("disk_free_bytes", 60, 5);
    datos.addAll(serie("disk_total_bytes", 100, 5));
    datos.forEach(m -> m.setMomento(m.getMomento().minusSeconds(45)));
    datos.addAll(serie("disk_free_bytes", 50, 3));
    datos.addAll(serie("disk_total_bytes", 100, 3));
    List<Recomendacion> alertas = detectar(datos);
    assertEquals(1, alertas.size());
    assertEquals("DISCO_BAJO", alertas.get(0).getTipo());
    assertTrue(alertas.get(0).getRiesgo().contains("Variacion anomala"));
  }

  @Test
  void detectaTendenciaHeapEnPuntosPorMinuto() {
    List<Metrica> datos = serie("jvm_memory_usage_after_gc", 0.1, 10);
    for (int i = 0; i < datos.size(); i++) {
      datos.get(i).setValor(0.1 + i * 0.005);
    }
    List<Recomendacion> alertas = detectar(datos);
    assertEquals(1, alertas.size());
    assertEquals("TENDENCIA_HEAP_ANOMALA", alertas.get(0).getTipo());
    assertTrue(alertas.get(0).getRiesgo().contains("2.00 pp/min"));
  }

  @Test
  void variabilidadHistoricaAltaNoGeneraFalsosSaltos() {
    List<Metrica> datos = serie("process_cpu_usage", 0.1, 5);
    for (int i = 0; i < datos.size(); i++) {
      datos.get(i).setValor(i % 2 == 0 ? 0.1 : 0.7);
      datos.get(i).setMomento(datos.get(i).getMomento().minusSeconds(45));
    }
    datos.addAll(serie("process_cpu_usage", 0.7, 3));
    assertTrue(detectar(datos).isEmpty());
  }

  private List<Metrica> serie(String nombre, double valor, int cantidad) {
    List<Metrica> datos = new ArrayList<>();
    for (int i = 0; i < cantidad; i++) {
      Metrica m = new Metrica();
      m.setNombre(nombre);
      m.setValor(valor);
      m.setMomento(AHORA.minusSeconds((cantidad - 1L - i) * 15));
      m.setLabels(LABELS);
      datos.add(m);
    }
    return datos;
  }

  private List<Recomendacion> detectar(List<Metrica> datos) {
    doAnswer(invocacion -> datos.stream()
            .filter(m -> m.getMomento().isAfter(invocacion.getArgument(1, Instant.class)))
            .sorted(Comparator.comparing(Metrica::getMomento)).toList())
        .when(metricas).findByNombreInAndMomentoAfterOrderByMomentoAsc(any(), any());
    List<Recomendacion> resultado = detector.detectar(AHORA);
    verify(metricas, atLeastOnce()).findByNombreInAndMomentoAfterOrderByMomentoAsc(
        any(), eq(AHORA.minusSeconds(120)));
    return resultado;
  }
}
