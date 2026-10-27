package com.redxela.model;

/** Ciclo de vida de un pedido. */
public enum OrderStatus {
    /** En lista de espera, listo para ser elegido por el planificador. */
    WAITING,
    /** En lista de espera porque no hay existencias suficientes de algún producto. */
    WAITING_STOCK,
    /** Ya tiene hilo trabajador y está obteniendo (o esperando) sus recursos. */
    ACQUIRING,
    /** Tiene todos sus recursos y está trabajando. */
    PROCESSING,
    COMPLETED
}
