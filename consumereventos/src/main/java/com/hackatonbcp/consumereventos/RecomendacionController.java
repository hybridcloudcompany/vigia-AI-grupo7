package com.hackatonbcp.consumereventos;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RecomendacionController {

  private final RecomendacionRepository recomendaciones;
  private final AnalisisRiesgoService analisis;

  @GetMapping("/recomendaciones")
  public List<Recomendacion> listar() {
    return recomendaciones.findAllByOrderByCreadaDesc();
  }

  @GetMapping("/analizar")
  public List<Recomendacion> analizarAhora() {
    analisis.analizar();
    return recomendaciones.findAllByOrderByCreadaDesc();
  }
}
