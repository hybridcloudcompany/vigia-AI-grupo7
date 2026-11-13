package com.hackatonbcp.consumereventos;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecomendacionRepository extends JpaRepository<Recomendacion, Long> {

  java.util.Optional<Recomendacion> findFirstByTipoAndOrigenOrderByCreadaDesc(
      String tipo, String origen);

  List<Recomendacion> findTop50ByPendienteSlackTrueOrderByCreadaAsc();

  boolean existsByTipoAndCreadaAfter(String tipo, Instant desde);

  List<Recomendacion> findAllByOrderByCreadaDesc();
}
