package com.redxela.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.springframework.stereotype.Component;

import com.redxela.exception.WarehouseFullException;
import com.redxela.model.Order;
import com.redxela.model.OrderItem;
import com.redxela.model.Product;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Área de Control de Inventario (consumidor).
 *
 * Se comunica con Recepción SOLO a través de un buzón (cola acotada bloqueante), nunca toca
 * sus estructuras. Es el patrón productor-consumidor:
 * - Si el buzón se llena, el productor se bloquea en put() (contrapresión): no se pierde ningún
 *   mensaje y el bloqueo dura solo hasta que este hilo consuma uno, así que no es indefinido.
 * - Si el buzón está vacío, este hilo duerme en take() sin gastar CPU.
 *
 * Además lleva las existencias: "disponible" (libre para reservar) y "reservado" (comprometido
 * por pedidos en proceso).
 */
@Component
public class InventoryControl {
    public static final int MAILBOX_CAPACITY = 15;
    private static final int INITIAL_STOCK = 100;

    private final BlockingQueue<ArrivalMessage> mailbox = new ArrayBlockingQueue<>(MAILBOX_CAPACITY);
    private final Map<Integer, Integer> available = new HashMap<>();
    private final Map<Integer, Integer> reserved = new HashMap<>();
    /** Protege available y reserved (las reservas deben ser atómicas: todo o nada). */
    private final Object stockLock = new Object();

    private final Warehouse warehouse;
    private final Catalog catalog;
    private final EventLog log;
    private volatile Runnable stockListener = () -> { };
    /** Inventario procesa "a su propio ritmo": tiempo simulado de registrar cada mensaje. */
    private volatile long processingDelayMs = 700;
    private Thread consumer;

    public InventoryControl(Warehouse warehouse, Catalog catalog, EventLog log) {
        this.warehouse = warehouse;
        this.catalog = catalog;
        this.log = log;
    }

    @PostConstruct
    void start() {
        // Existencias iniciales para poder simular desde el primer momento.
        for (Product p : catalog.all()) {
            Warehouse.StoreResult r = warehouse.store(p.id(), INITIAL_STOCK);
            available.put(p.id(), r.stored());
            reserved.put(p.id(), 0);
        }
        consumer = new Thread(this::consumeLoop, "inventario-consumidor");
        consumer.setDaemon(true);
        consumer.start();
    }

    @PreDestroy
    void stop() {
        if (consumer != null) consumer.interrupt();
    }

    /** Callback para avisar al planificador que hay nuevas existencias (re-evaluar pedidos en espera). */
    public void setStockListener(Runnable listener) {
        this.stockListener = listener;
    }

    /** Lo usa Recepción para enviar un mensaje; bloquea si el buzón está lleno. */
    public void send(ArrivalMessage message) throws InterruptedException {
        mailbox.put(message);
    }

    public int mailboxSize() {
        return mailbox.size();
    }

    public void setProcessingDelayMs(long ms) {
        this.processingDelayMs = Math.max(0, ms);
    }

    /** Hilo consumidor: saca mensajes del buzón y actualiza existencias y ubicaciones. */
    private void consumeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ArrivalMessage msg = mailbox.take();
                Thread.sleep(processingDelayMs);
                register(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // Un mensaje defectuoso no debe matar al hilo consumidor.
                log.log("INVENTARIO: error procesando mensaje: " + e.getMessage());
            }
        }
    }

    private void register(ArrivalMessage msg) {
        Product p = catalog.byId(msg.productId()).orElse(null);
        if (p == null) {
            log.log("INVENTARIO: mensaje con producto desconocido #" + msg.productId() + " descartado");
            return;
        }
        try {
            Warehouse.StoreResult r = warehouse.store(msg.productId(), msg.quantity());
            synchronized (stockLock) {
                available.merge(msg.productId(), r.stored(), Integer::sum);
            }
            log.log("INVENTARIO: +" + r.stored() + " " + p.name() + " en " + String.join(", ", r.locations()));
            if (r.rejected() > 0) {
                log.log("INVENTARIO: ALMACÉN SIN ESPACIO, " + r.rejected() + " unidades de " + p.name() + " no pudieron guardarse");
            }
        } catch (WarehouseFullException e) {
            log.log("INVENTARIO: " + e.getMessage() + ". Se rechazaron " + msg.quantity() + " " + p.name());
        }
        stockListener.run();
    }

    /** Agrupa las líneas del pedido por producto (un producto puede repetirse). */
    private Map<Integer, Integer> needed(Order o) {
        Map<Integer, Integer> need = new LinkedHashMap<>();
        for (OrderItem it : o.getItems()) need.merge(it.product().id(), it.quantity(), Integer::sum);
        return need;
    }

    /** Reserva TODAS las unidades del pedido o ninguna (atómico). */
    public boolean tryReserve(Order o) {
        Map<Integer, Integer> need = needed(o);
        synchronized (stockLock) {
            for (Map.Entry<Integer, Integer> e : need.entrySet()) {
                if (available.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
            }
            for (Map.Entry<Integer, Integer> e : need.entrySet()) {
                available.merge(e.getKey(), -e.getValue(), Integer::sum);
                reserved.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            return true;
        }
    }

    /** Devuelve la reserva (pedido abortado). */
    public void release(Order o) {
        synchronized (stockLock) {
            for (Map.Entry<Integer, Integer> e : needed(o).entrySet()) {
                available.merge(e.getKey(), e.getValue(), Integer::sum);
                reserved.merge(e.getKey(), -e.getValue(), Integer::sum);
            }
        }
        stockListener.run();
    }

    /** Confirma el despacho: saca físicamente las unidades del almacén y libera ubicaciones. */
    public void commit(Order o) {
        for (Map.Entry<Integer, Integer> e : needed(o).entrySet()) {
            warehouse.remove(e.getKey(), e.getValue());
        }
        synchronized (stockLock) {
            for (Map.Entry<Integer, Integer> e : needed(o).entrySet()) {
                reserved.merge(e.getKey(), -e.getValue(), Integer::sum);
            }
        }
    }

    /** Productos que faltan para poder reservar un pedido (texto para la lista de espera). */
    public String missingFor(Order o) {
        List<String> missing = new ArrayList<>();
        synchronized (stockLock) {
            for (Map.Entry<Integer, Integer> e : needed(o).entrySet()) {
                int have = available.getOrDefault(e.getKey(), 0);
                if (have < e.getValue()) {
                    String name = catalog.byId(e.getKey()).map(Product::name).orElse("#" + e.getKey());
                    missing.add(name + " (hay " + have + ", pide " + e.getValue() + ")");
                }
            }
        }
        return String.join("; ", missing);
    }

    public int availableOf(int productId) {
        synchronized (stockLock) {
            return available.getOrDefault(productId, 0);
        }
    }

    /** Existencias por producto para la interfaz. */
    public List<Map<String, Object>> stockSnapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (stockLock) {
            for (Product p : catalog.all()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.id());
                m.put("name", p.name());
                m.put("type", p.type().getLabel());
                m.put("available", available.getOrDefault(p.id(), 0));
                m.put("reserved", reserved.getOrDefault(p.id(), 0));
                out.add(m);
            }
        }
        return out;
    }
}
