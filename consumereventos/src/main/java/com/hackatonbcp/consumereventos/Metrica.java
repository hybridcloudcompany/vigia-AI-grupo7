package com.hackatonbcp.consumereventos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(indexes = @Index(name = "idx_metrica_nombre_ts", columnList = "nombre,momento"))
public class Metrica {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String nombre;

  private Instant momento;

  private double valor;

  @Column(length = 2000)
  private String labels;
}
