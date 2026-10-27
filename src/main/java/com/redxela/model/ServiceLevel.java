package com.redxela.model;

/** Nivel de servicio contratado por el cliente: define CUÁNDO se atiende un pedido. */
public enum ServiceLevel {
    EXPRES(1, "Exprés", 0),
    PRIORITARIO(2, "Prioritario", 10),
    ESTANDAR(3, "Estándar", 30),
    PROGRAMADO(4, "Programado", 60),
    ECONOMICO(5, "Económico", 120);

    private final int level;
    private final String label;
    /** Tiempo objetivo máximo de espera, en minutos simulados. */
    private final int maxWaitMinutes;

    ServiceLevel(int level, String label, int maxWaitMinutes) {
        this.level = level;
        this.label = label;
        this.maxWaitMinutes = maxWaitMinutes;
    }

    public int getLevel() { return level; }
    public String getLabel() { return label; }
    public int getMaxWaitMinutes() { return maxWaitMinutes; }

    public static ServiceLevel fromLevel(int level) {
        for (ServiceLevel s : values()) {
            if (s.level == level) return s;
        }
        throw new IllegalArgumentException("Nivel de servicio inválido: " + level);
    }
}
