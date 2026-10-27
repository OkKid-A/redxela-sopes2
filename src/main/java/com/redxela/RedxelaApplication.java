package com.redxela;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Punto de entrada: levanta Spring Boot, el simulador y la interfaz web (carpeta static). */
@SpringBootApplication
public class RedxelaApplication {
    public static void main(String[] args) {
        SpringApplication.run(RedxelaApplication.class, args);
    }
}
