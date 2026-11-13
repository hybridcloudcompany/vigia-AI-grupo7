package com.hackatonbcp.consumereventos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

class AlertaSlackTests {

  private static final String ORIGEN = "instance=localhost:8080,job=temperatura";
  private final MetricaRepository metricas = mock(MetricaRepository.class);
  private final RecomendacionRepository recomendaciones = mock(RecomendacionRepository.class);
  private final SlackClient slack = mock(SlackClient.class);
  private final AnomaliasMetricasService anomalias = mock(AnomaliasMetricasService.class);
  private final AnalisisRiesgoService analisis =
      new AnalisisRiesgoService(metricas, recomendaciones, slack, anomalias);

  @BeforeEach
  void preparar() {
    when(metricas.buscarTargetsDisponibilidad()).thenReturn(List.of(ORIGEN));
    Metrica caida = muestra(0, Instant.now());
    when(metricas.findByNombreAndLabelsOrderByMomentoDesc(eq("up"), eq(ORIGEN), any(Pageable.class)))
        .thenReturn(List.of(caida, caida, caida));
  }

  @Test
  void registraUnaCaidaConSuServicioYPendienteDeNotificar() {
    analisis.analizar();

    ArgumentCaptor<Recomendacion> captor = ArgumentCaptor.forClass(Recomendacion.class);
    verify(recomendaciones).save(captor.capture());
    assertTrue(captor.getValue().getRiesgo().contains("job=temperatura"));
    assertTrue(captor.getValue().getPendienteSlack());
  }

  @Test
  void noDuplicaUnaCaidaActivaDuranteElSilencio() {
    anterior();
    analisis.analizar();
    verify(recomendaciones, never()).save(any());
  }

  @Test
  void repiteUnaCaidaActivaDespuesDeDosMinutos() {
    Recomendacion anterior = new Recomendacion();
    anterior.setCreada(Instant.now().minusSeconds(121));
    when(recomendaciones.findFirstByTipoAndOrigenOrderByCreadaDesc("SERVICIO_CAIDO", ORIGEN))
        .thenReturn(Optional.of(anterior));

    analisis.analizar();

    verify(recomendaciones).save(any(Recomendacion.class));
  }

  @Test
  void alertaDeNuevoSiSeRecuperoYVolvioACaerDentroDeDosMinutos() {
    anterior();
    when(metricas.findFirstByNombreAndLabelsAndValorGreaterThanOrderByMomentoDesc("up", ORIGEN, 0))
        .thenReturn(Optional.of(muestra(1, Instant.now().minusSeconds(60))));
    analisis.analizar();
    verify(recomendaciones).save(any(Recomendacion.class));
  }

  @Test
  void ignoraMuestrasAntiguas() {
    Metrica antigua = muestra(0, Instant.now().minusSeconds(300));
    when(metricas.findByNombreAndLabelsOrderByMomentoDesc(eq("up"), eq(ORIGEN), any(Pageable.class)))
        .thenReturn(List.of(antigua, antigua, antigua));
    analisis.analizar();
    verify(recomendaciones, never()).save(any());
  }

  @Test
  void noAlertaMientrasElServicioEstaArriba() {
    Metrica arriba = muestra(1, Instant.now());
    when(metricas.findByNombreAndLabelsOrderByMomentoDesc(eq("up"), eq(ORIGEN), any(Pageable.class)))
        .thenReturn(List.of(arriba, arriba, arriba));
    analisis.analizar();
    verify(recomendaciones, never()).save(any());
  }

  @Test
  void notificaRecuperacionConUnaMuestraSaludableTrasUnaCaida() {
    Metrica arriba = muestra(1, Instant.now());
    when(metricas.findByNombreAndLabelsOrderByMomentoDesc(eq("up"), eq(ORIGEN), any(Pageable.class)))
        .thenReturn(List.of(arriba));
    when(metricas.findFirstByNombreAndLabelsAndValorOrderByMomentoDesc("up", ORIGEN, 0))
        .thenReturn(Optional.of(muestra(0, Instant.now().minusSeconds(30))));

    analisis.analizar();

    ArgumentCaptor<Recomendacion> captor = ArgumentCaptor.forClass(Recomendacion.class);
    verify(recomendaciones).save(captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals("SERVICIO_RECUPERADO", captor.getValue().getTipo());
    org.junit.jupiter.api.Assertions.assertEquals("INFO", captor.getValue().getSeveridad());
    assertTrue(captor.getValue().getPendienteSlack());
    assertTrue(captor.getValue().getRiesgo().contains(ORIGEN));
  }

  @Test
  void noRepiteUnaRecuperacionYaRegistrada() {
    Metrica arriba = muestra(1, Instant.now());
    when(metricas.findByNombreAndLabelsOrderByMomentoDesc(eq("up"), eq(ORIGEN), any(Pageable.class)))
        .thenReturn(List.of(arriba));
    when(metricas.findFirstByNombreAndLabelsAndValorOrderByMomentoDesc("up", ORIGEN, 0))
        .thenReturn(Optional.of(muestra(0, Instant.now().minusSeconds(120))));
    Recomendacion recuperacion = new Recomendacion();
    recuperacion.setCreada(Instant.now().minusSeconds(60));
    when(recomendaciones.findFirstByTipoAndOrigenOrderByCreadaDesc("SERVICIO_RECUPERADO", ORIGEN))
        .thenReturn(Optional.of(recuperacion));

    analisis.analizar();
    verify(recomendaciones, never()).save(any());
  }

  @Test
  void notificaUnaSegundaRecuperacionSinEsperarElSilencio() {
    Metrica arriba = muestra(1, Instant.now());
    when(metricas.findByNombreAndLabelsOrderByMomentoDesc(eq("up"), eq(ORIGEN), any(Pageable.class)))
        .thenReturn(List.of(arriba));
    when(metricas.findFirstByNombreAndLabelsAndValorOrderByMomentoDesc("up", ORIGEN, 0))
        .thenReturn(Optional.of(muestra(0, Instant.now().minusSeconds(30))));
    Recomendacion recuperacion = new Recomendacion();
    recuperacion.setCreada(Instant.now().minusSeconds(120));
    when(recomendaciones.findFirstByTipoAndOrigenOrderByCreadaDesc("SERVICIO_RECUPERADO", ORIGEN))
        .thenReturn(Optional.of(recuperacion));

    analisis.analizar();
    verify(recomendaciones).save(any(Recomendacion.class));
  }

  @Test
  void anomaliasSeGuardanYSeNotificanSinDuplicadosPorOrigen() {
    when(metricas.buscarTargetsDisponibilidad()).thenReturn(List.of());
    Recomendacion alerta = new Recomendacion();
    alerta.setTipo("CPU_ANOMALA");
    alerta.setOrigen(ORIGEN);
    alerta.setSeveridad("MEDIA");
    alerta.setRiesgo("CPU 85%");
    alerta.setAccion("Revisar carga");
    when(anomalias.detectar(any())).thenReturn(List.of(alerta));
    when(recomendaciones.findFirstByTipoAndOrigenOrderByCreadaDesc("CPU_ANOMALA", ORIGEN))
        .thenReturn(Optional.empty(), Optional.of(alerta));
    alerta.setCreada(Instant.now());
    analisis.analizar();
    analisis.analizar();
    verify(recomendaciones, times(1)).save(any());
  }

  @Test
  void permiteEscalarUnaAnomaliaDeMediaAAltaDuranteElSilencio() {
    when(metricas.buscarTargetsDisponibilidad()).thenReturn(List.of());
    Recomendacion anterior = new Recomendacion();
    anterior.setSeveridad("MEDIA");
    anterior.setCreada(Instant.now());
    Recomendacion alerta = new Recomendacion();
    alerta.setTipo("CPU_ANOMALA");
    alerta.setOrigen(ORIGEN);
    alerta.setSeveridad("ALTA");
    when(anomalias.detectar(any())).thenReturn(List.of(alerta));
    when(recomendaciones.findFirstByTipoAndOrigenOrderByCreadaDesc("CPU_ANOMALA", ORIGEN))
        .thenReturn(Optional.of(anterior));
    analisis.analizar();
    verify(recomendaciones).save(any());
  }

  @Test
  void reintentaPendientesHastaQueSlackConfirme() {
    when(metricas.buscarTargetsDisponibilidad()).thenReturn(List.of());
    Recomendacion r = new Recomendacion();
    r.setPendienteSlack(true);
    when(recomendaciones.findTop50ByPendienteSlackTrueOrderByCreadaAsc()).thenReturn(List.of(r));
    when(slack.notificar(r)).thenReturn(false, true);

    analisis.analizar();
    assertTrue(r.getPendienteSlack());
    verify(recomendaciones, never()).save(any());
    analisis.analizar();
    assertFalse(r.getPendienteSlack());
    verify(recomendaciones).save(r);
    verify(slack, times(2)).notificar(r);
  }

  private void anterior() {
    Recomendacion anterior = new Recomendacion();
    anterior.setCreada(Instant.now().minusSeconds(90));
    when(recomendaciones.findFirstByTipoAndOrigenOrderByCreadaDesc("SERVICIO_CAIDO", ORIGEN))
        .thenReturn(Optional.of(anterior));
  }

  private Metrica muestra(double valor, Instant momento) {
    Metrica m = new Metrica();
    m.setValor(valor);
    m.setMomento(momento);
    m.setLabels(ORIGEN);
    return m;
  }
}
