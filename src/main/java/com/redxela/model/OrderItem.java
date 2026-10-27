package com.redxela.model;

/** Línea de un pedido: producto y cantidad de unidades. */
public record OrderItem(Product product, int quantity) {
}
