package com.hackatonbcp.consumereventos;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MyConsumer {

  private final MetricaRepository repository;

  @KafkaListener(topics = "metricas-prometheus", groupId = "mi-grupo-de-consumo")
  public void consumir(MiDTO mensaje) {
    // Los buckets de histograma generan mucho volumen y no se usan en las reglas
    if (mensaje.getName() == null || mensaje.getName().endsWith("_bucket")) {
      return;
    }
    try {
      double valor = Double.parseDouble(mensaje.getValue());
      if (Double.isNaN(valor) || Double.isInfinite(valor)) {
        return;
      }
      Metrica metrica = new Metrica();
      metrica.setNombre(mensaje.getName());
      metrica.setMomento(Instant.parse(mensaje.getTimestamp()));
      metrica.setValor(valor);
      metrica.setLabels(formatear(mensaje.getLabels()));
      repository.save(metrica);
    } catch (RuntimeException e) {
      log.warn("Mensaje descartado ({}): {}", e.getMessage(), mensaje);
    }
  }

  private String formatear(Map<String, String> labels) {
    if (labels == null) {
      return "";
    }
    return labels.entrySet().stream()
        .filter(e -> !"__name__".equals(e.getKey()))
        .sorted(Map.Entry.comparingByKey())
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining(","));
  }
}
