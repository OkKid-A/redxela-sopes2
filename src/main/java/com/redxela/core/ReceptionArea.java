package com.redxela.core;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.redxela.exception.RedxelaException;
import com.redxela.model.Product;
import com.redxela.model.ResourceType;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Área de Recepción de Mercadería (productor).
 *
 * Los camiones que llegan se encolan en una cola propia (la llamada HTTP no se queda esperando).
 * Un hilo de recepción toma cada camión, usa el escáner compartido (que también necesita el
 * despacho) y envía un mensaje a Inventario por el buzón acotado. Si Inventario es más lento,
 * el buzón se llena y este hilo se bloquea en send() (contrapresión) sin perder mensajes.
 */
@Component
public class ReceptionArea {
    private static final String HOLDER = "RECEPCION";

    private final BlockingQueue<ArrivalMessage> trucks = new LinkedBlockingQueue<>();
    private final AtomicInteger sent = new AtomicInteger();
    private final ResourceManager resources;
    private final InventoryControl inventory;
    private final Catalog catalog;
    private final EventLog log;
    private Thread worker;

    public ReceptionArea(ResourceManager resources, InventoryControl inventory, Catalog catalog, EventLog log) {
        this.resources = resources;
        this.inventory = inventory;
        this.catalog = catalog;
        this.log = log;
    }

    @PostConstruct
    void start() {
        worker = new Thread(this::receiveLoop, "recepcion-productor");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        if (worker != null) worker.interrupt();
    }

    /** Registra la llegada de un camión con mercadería. Retorna de inmediato. */
    public void registerArrival(int productId, int quantity) {
        Product p = catalog.byId(productId).orElseThrow(() -> new RedxelaException("Producto inexistente: " + productId));
        if (quantity <= 0) throw new RedxelaException("La cantidad recibida debe ser mayor a cero");
        trucks.add(new ArrivalMessage(productId, quantity, System.currentTimeMillis()));
        log.log("RECEPCIÓN: llegó camión con " + quantity + " " + p.name());
    }

    private void receiveLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ArrivalMessage msg = trucks.take();
                // El escáner es un recurso compartido con el despacho: se pide y se suelta pronto.
                resources.acquire(ResourceType.SCANNER, HOLDER);
                try {
                    Thread.sleep(300);
                } finally {
                    resources.release(ResourceType.SCANNER, HOLDER);
                }
                inventory.send(msg); // bloquea si el buzón está lleno
                sent.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.log("RECEPCIÓN: error " + e.getMessage());
            }
        }
    }

    /** Camiones esperando ser escaneados y enviados. */
    public int trucksWaiting() {
        return trucks.size();
    }

    public int messagesSent() {
        return sent.get();
    }
}
