package com.hackatonbcp.consumereventos;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SlackClientTests {

  private static final String WEBHOOK = "https://hooks.slack.com/services/test";
  private MockRestServiceServer server;
  private RestClient rest;

  @BeforeEach
  void preparar() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    rest = builder.build();
  }

  @Test
  void enviaResumenComoPostJson() {
    server.expect(requestTo(WEBHOOK))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(content().json("""
            {"text":"Alerta de monitoreo [CRITICA] SERVICIO_CAIDO\\nFecha (UTC): 2026-11-13T16:00:00Z\\nRiesgo: Servicio sin respuesta\\nAccion recomendada: Revisar conectividad\\nDashboard: http://localhost:8086\\n"}
            """))
        .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

    assertTrue(new SlackClient(WEBHOOK, "http://localhost:8086", rest).notificar(alerta()));

    server.verify();
  }

  @Test
  void errorDeSlackNoInterrumpeElMonitoreo() {
    server.expect(requestTo(WEBHOOK)).andRespond(withServerError());
    assertFalse(assertDoesNotThrow(() ->
        new SlackClient(WEBHOOK, "http://localhost:8086", rest).notificar(alerta())));
    server.verify();
  }

  @Test
  void sinWebhookNoEnviaPeticiones() {
    assertFalse(new SlackClient("", "http://localhost:8086", rest).notificar(alerta()));
    server.verify();
  }

  private Recomendacion alerta() {
    Recomendacion alerta = new Recomendacion();
    alerta.setCreada(Instant.parse("2026-11-13T16:00:00Z"));
    alerta.setTipo("SERVICIO_CAIDO");
    alerta.setSeveridad("CRITICA");
    alerta.setRiesgo("Servicio sin respuesta");
    alerta.setAccion("Revisar conectividad");
    return alerta;
  }
}
