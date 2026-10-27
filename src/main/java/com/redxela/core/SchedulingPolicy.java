package com.redxela.core;

import java.util.Comparator;

import com.redxela.model.Order;

/**
 * Políticas de planificación (qué pedido de la lista de espera se atiende primero).
 * Cada política es un Comparator; el planificador elige el mínimo según la política activa,
 * por lo que se puede cambiar en caliente sin reiniciar nada.
 */
public enum SchedulingPolicy {
    PRIORIDAD_ESTRICTA("Prioridad estricta",
            "Siempre el nivel de servicio más alto primero; a igual nivel, FCFS (llegada). Puede causar inanición de niveles bajos."),
    ENVEJECIMIENTO("Prioridad con envejecimiento",
            "Como la estricta, pero cada " + 15 + " min simulados de espera el pedido sube un nivel de prioridad efectiva. Evita la inanición."),
    SJF("Menor cantidad de artículos primero",
            "Shortest Job First: se atiende primero el pedido con menos unidades (menos trabajo). Minimiza el tiempo medio de espera, pero los pedidos grandes pueden esperar mucho.");

    /** Minutos simulados de espera que equivalen a subir un nivel de prioridad (envejecimiento). */
    public static final double AGING_STEP_MINUTES = 15.0;

    private final String label;
    private final String description;

    SchedulingPolicy(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() { return label; }
    public String getDescription() { return description; }

    /** Prioridad efectiva: el nivel menos las "promociones" ganadas por esperar. Menor = más urgente. */
    public static double effectivePriority(Order o, long now, long minuteMs) {
        double waitedMinutes = (now - o.getCreatedAt()) / (double) minuteMs;
        return o.getLevel().getLevel() - waitedMinutes / AGING_STEP_MINUTES;
    }

    public Comparator<Order> comparator(long now, long minuteMs) {
        switch (this) {
            case ENVEJECIMIENTO:
                return Comparator.comparingDouble((Order o) -> effectivePriority(o, now, minuteMs))
                        .thenComparingLong(Order::getCreatedAt);
            case SJF:
                return Comparator.comparingInt(Order::getTotalUnits)
                        .thenComparingInt((Order o) -> o.getLevel().getLevel())
                        .thenComparingLong(Order::getCreatedAt);
            default:
                return Comparator.comparingInt((Order o) -> o.getLevel().getLevel())
                        .thenComparingLong(Order::getCreatedAt);
        }
    }

    /** Texto del criterio de orden de un pedido, para mostrarlo en la lista de espera. */
    public String describeKey(Order o, long now, long minuteMs) {
        switch (this) {
            case ENVEJECIMIENTO:
                return String.format("prioridad efectiva %.2f", effectivePriority(o, now, minuteMs));
            case SJF:
                return o.getTotalUnits() + " unidades";
            default:
                return "nivel " + o.getLevel().getLevel();
        }
    }
}
