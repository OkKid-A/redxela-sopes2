package com.redxela.model;

/**
 * Recursos compartidos del centro. Cada uno se modela con un semáforo contador cuyo valor
 * inicial es la capacidad.
 *
 * IMPORTANTE: el orden de declaración (ordinal) es el ORDEN GLOBAL de adquisición que se usa
 * como técnica de prevención de deadlock (se rompe la condición de "espera circular").
 */
public enum ResourceType {
    STAFF("Encargados de bodega", 10),
    FORKLIFT("Montacargas", 3),
    PACKING("Estaciones de empaque", 6),
    QUALITY("Control de calidad", 4),
    SCANNER("Sistema de escaneo", 1),
    LOADING("Áreas de carga", 2);

    private final String label;
    private final int capacity;

    ResourceType(String label, int capacity) {
        this.label = label;
        this.capacity = capacity;
    }

    public String getLabel() { return label; }
    public int getCapacity() { return capacity; }
}
