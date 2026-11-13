package com.hackatonbcp.consumereventos;

import java.util.List;

public record Pronostico(
    String servicio,
    int probabilidad,
    String nivel,
    String estado,
    String detalle,
    String causa,
    List<String> acciones,
    List<Integer> proyeccion,
    List<Senal> senales,
    boolean generadoPorIa) {

  public record Senal(String nombre, String valor, String normal, String nivel) {}

  public String icono() {
    return switch (nivel) {
      case "tormenta" -> "⛈";
      case "nublado" -> "☁";
      default -> "☀";
    };
  }
}
