package com.redxela.core;

/** Mensaje que Recepción envía a Inventario: qué producto llegó y cuántas unidades. */
public record ArrivalMessage(int productId, int quantity, long timestamp) {
}
