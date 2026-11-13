package com.hackatonbcp.consumereventos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
public class Recomendacion {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Instant creada;

  private String tipo;

  @Column(length = 2000)
  private String origen;

  private Boolean pendienteSlack = false;

  private String severidad;

  @Column(length = 1000)
  private String riesgo;

  @Column(length = 1000)
  private String accion;
}
