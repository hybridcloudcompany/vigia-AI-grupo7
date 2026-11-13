package com.hackatonbcp.consumereventos;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MetricaRepository extends JpaRepository<Metrica, Long> {

  @Query("select distinct m.labels from Metrica m where m.nombre = 'up'")
  List<String> buscarTargetsDisponibilidad();

  List<Metrica> findByNombreAndLabelsOrderByMomentoDesc(
      String nombre, String labels, Pageable pageable);

  java.util.Optional<Metrica> findFirstByNombreAndLabelsAndValorGreaterThanOrderByMomentoDesc(
      String nombre, String labels, double valor);

  java.util.Optional<Metrica> findFirstByNombreAndLabelsAndValorOrderByMomentoDesc(
      String nombre, String labels, double valor);

  List<Metrica> findByNombreOrderByMomentoDesc(String nombre, Pageable pageable);

  List<Metrica> findByNombreAndMomentoAfterOrderByMomentoAsc(String nombre, Instant desde);

  List<Metrica> findByNombreInAndMomentoAfterOrderByMomentoAsc(
      List<String> nombres, Instant desde);

  long deleteByMomentoBefore(Instant limite);
}
