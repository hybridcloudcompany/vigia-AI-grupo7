package com.hackatonbcp.consumereventos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ConsumereventosApplication {

	public static void main(String[] args) {
		SpringApplication.run(ConsumereventosApplication.class, args);
	}

}
