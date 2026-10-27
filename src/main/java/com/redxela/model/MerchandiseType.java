package com.redxela.model;

import java.util.List;

/**
 * Tipo de mercancía: define QUÉ recursos necesita un pedido y en qué orden "natural" los pide.
 * Cada tipo requiere una combinación distinta de recursos.
 *
 * ELECTRONICO y PERECEDERO comparten SCANNER y LOADING pero los piden en orden inverso:
 * es el escenario de contención que, sin prevención, produce deadlock.
 */
public enum MerchandiseType {
    FRAGIL("Frágil", "Vidrio y cerámica: requiere manipulación manual cuidadosa, empaque especial e inspección.",
            ResourceType.STAFF, ResourceType.PACKING, ResourceType.QUALITY),
    PESADO("Pesado", "Metales y materiales de construcción: se mueve con montacargas y personal, sale por carga.",
            ResourceType.FORKLIFT, ResourceType.STAFF, ResourceType.LOADING),
    VOLUMINOSO("Voluminoso", "Electrodomésticos y muebles: montacargas, empaque especial y área de carga.",
            ResourceType.FORKLIFT, ResourceType.PACKING, ResourceType.LOADING),
    ELECTRONICO("Electrónico", "Alto valor: se escanea primero, sale por carga y se empaca al final.",
            ResourceType.SCANNER, ResourceType.LOADING, ResourceType.PACKING),
    PERECEDERO("Perecedero", "Alimentos refrigerados: reserva carga, se escanea y pasa control de calidad.",
            ResourceType.LOADING, ResourceType.SCANNER, ResourceType.QUALITY);

    private final String label;
    private final String description;
    private final List<ResourceType> sequence;

    MerchandiseType(String label, String description, ResourceType... sequence) {
        this.label = label;
        this.description = description;
        this.sequence = List.of(sequence);
    }

    public String getLabel() { return label; }
    public String getDescription() { return description; }
    /** Recursos requeridos en su orden natural de solicitud. */
    public List<ResourceType> getSequence() { return sequence; }
}
