package com.turnos;

import org.springframework.boot.SpringApplication;

public class TestTurnosBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(TurnosBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
