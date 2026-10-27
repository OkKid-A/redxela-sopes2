package com.redxela.model;

/** Cuenta de cliente; el nivel de servicio se elige al crearla. */
public record Customer(int id, String name, ServiceLevel level) {
}
