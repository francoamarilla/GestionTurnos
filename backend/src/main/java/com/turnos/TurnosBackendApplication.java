package com.turnos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// The WhatsApp webhook acks immediately and processes off-thread (spec
// section 11), which needs @EnableAsync. The reminder sweep job (spec
// section 6) needs @EnableScheduling.
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class TurnosBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(TurnosBackendApplication.class, args);
	}

}
