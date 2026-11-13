package com.hackatonbcp.consumereventos;

import java.util.Map;
import lombok.Data;

@Data
public class MiDTO {

  private String name;
  private String timestamp;
  private String value;
  private Map<String, String> labels;

}
