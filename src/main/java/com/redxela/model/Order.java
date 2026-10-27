package com.redxela.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.redxela.core.SchedulingPolicy;

/**
 * Pedido del centro de distribución.
 *
 * Los campos de estado son volatile porque los escribe el hilo trabajador que procesa el pedido
 * y los lee el hilo HTTP que arma la vista de la interfaz (garantiza visibilidad entre hilos).
 */
public class Order {
    private final int id;
    private final Customer customer;
    private final ServiceLevel level;
    private final List<OrderItem> items;
    private final int totalUnits;
    private final long createdAt;

    public volatile OrderStatus status = OrderStatus.WAITING;
    /** Instante en que el pedido obtuvo todos sus recursos y empezó a trabajar. */
    public volatile long startedAt;
    public volatile long finishedAt;
    /** No puede ser elegido antes de este instante (enfriamiento tras ser víctima de deadlock). */
    public volatile long notBefore;
    public volatile int progress;
    public volatile long workTotalMs;
    public volatile long workDoneMs;
    public volatile ResourceType waitingFor;
    public final List<ResourceType> held = new CopyOnWriteArrayList<>();
    public volatile Thread worker;
    public volatile int retries;
    public volatile SchedulingPolicy policyUsed;

    public Order(int id, Customer customer, List<OrderItem> items) {
        this.id = id;
        this.customer = customer;
        this.level = customer.level();
        this.items = List.copyOf(items);
        this.totalUnits = items.stream().mapToInt(OrderItem::quantity).sum();
        this.createdAt = System.currentTimeMillis();
    }

    public int getId() { return id; }
    public Customer getCustomer() { return customer; }
    public ServiceLevel getLevel() { return level; }
    public List<OrderItem> getItems() { return items; }
    public int getTotalUnits() { return totalUnits; }
    public long getCreatedAt() { return createdAt; }

    /** Nombre con el que este pedido figura como "dueño" de recursos. */
    public String holderName() { return "PED-" + id; }

    public static int idFromHolder(String holder) {
        if (holder == null || !holder.startsWith("PED-")) return -1;
        try {
            return Integer.parseInt(holder.substring(4));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Tipos de mercancía presentes en el pedido. */
    public Set<MerchandiseType> types() {
        Set<MerchandiseType> set = new LinkedHashSet<>();
        for (OrderItem it : items) set.add(it.product().type());
        return set;
    }

    /** Unión de los recursos de todos los tipos, en su orden natural de solicitud. */
    public List<ResourceType> requiredSequence() {
        Set<ResourceType> set = new LinkedHashSet<>();
        for (OrderItem it : items) set.addAll(it.product().type().getSequence());
        return new ArrayList<>(set);
    }

    /** Tiempo objetivo de espera en ms reales (el nivel Exprés se toma como "casi instantáneo": 3 s). */
    public long targetWaitMs(long minuteMs) {
        return Math.max(level.getMaxWaitMinutes() * minuteMs, 3000);
    }
}
