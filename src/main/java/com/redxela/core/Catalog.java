package com.redxela.core;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.redxela.model.MerchandiseType;
import com.redxela.model.Product;

/** Catálogo fijo de productos (no se crean productos desde la interfaz). */
@Component
public class Catalog {
    private final List<Product> products = List.of(
            new Product(1, "Vasos de cristal", MerchandiseType.FRAGIL),
            new Product(2, "Figuras de porcelana", MerchandiseType.FRAGIL),
            new Product(3, "Barras de acero", MerchandiseType.PESADO),
            new Product(4, "Sacos de cemento", MerchandiseType.PESADO),
            new Product(5, "Refrigeradora", MerchandiseType.VOLUMINOSO),
            new Product(6, "Sofá modular", MerchandiseType.VOLUMINOSO),
            new Product(7, "Laptop", MerchandiseType.ELECTRONICO),
            new Product(8, "Teléfono móvil", MerchandiseType.ELECTRONICO),
            new Product(9, "Lácteos refrigerados", MerchandiseType.PERECEDERO),
            new Product(10, "Frutas y verduras", MerchandiseType.PERECEDERO));

    public List<Product> all() {
        return products;
    }

    public Optional<Product> byId(int id) {
        return products.stream().filter(p -> p.id() == id).findFirst();
    }

    public List<Product> ofType(MerchandiseType type) {
        return products.stream().filter(p -> p.type() == type).toList();
    }
}
