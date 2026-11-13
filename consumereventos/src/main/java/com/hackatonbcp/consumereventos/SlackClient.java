package com.hackatonbcp.consumereventos;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class SlackClient {

  private final String webhook;
  private final String dashboardUrl;
  private final RestClient restClient;

  @Autowired
  public SlackClient(
      @Value("${app.slack.webhook-url:}") String webhook,
      @Value("${app.slack.dashboard-url:http://localhost:8086}") String dashboardUrl) {
    this(webhook, dashboardUrl, crearCliente());
  }

  SlackClient(String webhook, String dashboardUrl, RestClient restClient) {
    this.webhook = webhook.strip();
    this.dashboardUrl = dashboardUrl;
    this.restClient = restClient;
    if (this.webhook.isEmpty()) {
      log.warn("Notificaciones Slack desactivadas: configure SLACK_WEBHOOK_URL.");
    }
  }

  public boolean notificar(Recomendacion alerta) {
    if (webhook.isEmpty()) {
      return false;
    }
    String encabezado = "SERVICIO_RECUPERADO".equals(alerta.getTipo())
        ? "Recuperacion de servicio" : "Alerta de monitoreo";
    String texto = """
        %s [%s] %s
        Fecha (UTC): %s
        Riesgo: %s
        Accion recomendada: %s
        Dashboard: %s
        """.formatted(
            encabezado, alerta.getSeveridad(), alerta.getTipo(), alerta.getCreada(),
            alerta.getRiesgo(), alerta.getAccion(), dashboardUrl);
    try {
      String respuesta = restClient.post()
          .uri(webhook)
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("text", texto))
          .retrieve()
          .body(String.class);
      if (!"ok".equals(respuesta == null ? null : respuesta.strip())) {
        log.error("Slack no confirmo la alerta {} (id={}).", alerta.getTipo(), alerta.getId());
        return false;
      }
      log.info("Alerta {} enviada a Slack (id={}).", alerta.getTipo(), alerta.getId());
      return true;
    } catch (RestClientException e) {
      // Las excepciones HTTP pueden contener la URL secreta del webhook.
      log.error("No se pudo enviar la alerta {} a Slack (id={}, error={}).",
          alerta.getTipo(), alerta.getId(), e.getClass().getSimpleName());
      return false;
    }
  }

  private static RestClient crearCliente() {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(5));
    return RestClient.builder().requestFactory(factory).build();
  }
}
