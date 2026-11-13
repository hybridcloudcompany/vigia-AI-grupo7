package com.hackatonbcp.consumereventos;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
@RequiredArgsConstructor
public class PronosticoController {

  static final int REFRESCO_SEGUNDOS = 60;

  private final PronosticoService servicio;
  private final RecomendacionRepository recomendaciones;

  @GetMapping("/")
  public String general(Model model) {
    List<Pronostico> lista = servicio.pronosticos();
    model.addAttribute("pronosticos", lista);
    model.addAttribute("principal", lista.isEmpty() || lista.get(0).probabilidad() < 30 ? null : lista.get(0));
    model.addAttribute("recientes", recomendaciones.findAllByOrderByCreadaDesc().stream().limit(5).toList());
    cabecera(model);
    return "index";
  }

  @GetMapping("/pronostico/{nombre}")
  public String extendido(@PathVariable String nombre, Model model) {
    Pronostico p = servicio.pronostico(nombre).orElse(null);
    if (p == null) {
      return "redirect:/";
    }
    model.addAttribute("p", p);
    model.addAttribute("recientes", recomendaciones.findAllByOrderByCreadaDesc().stream().limit(5).toList());
    cabecera(model);
    return "extendido";
  }

  private void cabecera(Model model) {
    model.addAttribute("hora", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
    model.addAttribute("refresco", REFRESCO_SEGUNDOS);
    model.addAttribute("iaActiva", servicio.iaActiva());
  }
}
