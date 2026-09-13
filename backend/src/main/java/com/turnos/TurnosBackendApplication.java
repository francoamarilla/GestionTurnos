package com.turnos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

// The WhatsApp webhook acks immediately and processes off-thread (spec
// section 11), which needs this.
@SpringBootApplication
@EnableAsync
public class TurnosBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(TurnosBackendApplication.class, args);
	}

}
