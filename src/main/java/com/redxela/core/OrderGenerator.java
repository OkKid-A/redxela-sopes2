package com.redxela.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.redxela.model.Customer;
import com.redxela.model.MerchandiseType;
import com.redxela.model.Product;
import com.redxela.model.ServiceLevel;

import jakarta.annotation.PreDestroy;

/**
 * Generador automático de pedidos: simula la llegada continua de clientes de todos los niveles
 * sin límite de cantidad. Opcionalmente también simula camiones con mercadería.
 */
@Component
public class OrderGenerator {
    /** Probabilidad relativa de cada nivel (1..5): hay más pedidos de niveles bajos. */
    private static final int[] LEVEL_WEIGHTS = {10, 15, 30, 25, 20};

    private final DistributionCenter center;
    private final CustomerRegistry customers;
    private final ReceptionArea reception;
    private final InventoryControl inventory;
    private final Catalog catalog;
    private final EventLog log;

    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile boolean autoArrivals;
    private volatile int intervalMs = 2500;
    private int tick;

    public OrderGenerator(DistributionCenter center, CustomerRegistry customers, ReceptionArea reception,
                          InventoryControl inventory, Catalog catalog, EventLog log) {
        this.center = center;
        this.customers = customers;
        this.reception = reception;
        this.inventory = inventory;
        this.catalog = catalog;
        this.log = log;
    }

    public synchronized void start(int intervalMs, boolean autoArrivals) {
        stop();
        this.intervalMs = Math.max(100, intervalMs);
        this.autoArrivals = autoArrivals;
        this.running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "generador");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::generate, 0, this.intervalMs, TimeUnit.MILLISECONDS);
        log.log("GENERADOR: iniciado (un pedido cada " + this.intervalMs + " ms" + (autoArrivals ? ", con camiones automáticos" : "") + ")");
    }

    @PreDestroy
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            if (running) log.log("GENERADOR: detenido");
        }
        running = false;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("intervalMs", intervalMs);
        m.put("autoArrivals", autoArrivals);
        return m;
    }

    /** Un ciclo del generador. Captura todo: si una tarea periódica lanza excepción, deja de ejecutarse. */
    private void generate() {
        try {
            generateOrder();
            tick++;
            if (autoArrivals && tick % 3 == 0) generateArrival();
        } catch (RuntimeException e) {
            log.log("GENERADOR: error " + e.getMessage());
        }
    }

    private void generateOrder() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        ServiceLevel level = ServiceLevel.fromLevel(weightedLevel(rnd));
        List<Customer> pool = customers.ofLevel(level);
        if (pool.isEmpty()) pool = customers.all();
        Customer customer = pool.get(rnd.nextInt(pool.size()));

        // Un tipo de mercancía al azar y 1 o 2 de sus productos, con cantidades pequeñas más probables.
        MerchandiseType type = MerchandiseType.values()[rnd.nextInt(MerchandiseType.values().length)];
        List<Product> candidates = catalog.ofType(type);
        List<DistributionCenter.ItemRequest> items = new ArrayList<>();
        int lines = 1 + rnd.nextInt(Math.min(2, candidates.size()));
        for (int i = 0; i < lines; i++) {
            int qty = 1 + (int) Math.round(Math.pow(rnd.nextDouble(), 2) * 59);
            items.add(new DistributionCenter.ItemRequest(candidates.get(i).id(), qty));
        }
        center.createOrder(customer.id(), items);
    }

    /** Camión con mercadería del producto que tenga menos existencias. */
    private void generateArrival() {
        Product lowest = catalog.all().stream()
                .min(Comparator.comparingInt(p -> inventory.availableOf(p.id())))
                .orElseThrow();
        reception.registerArrival(lowest.id(), 60 + ThreadLocalRandom.current().nextInt(90));
    }

    private int weightedLevel(ThreadLocalRandom rnd) {
        int total = 0;
        for (int w : LEVEL_WEIGHTS) total += w;
        int r = rnd.nextInt(total);
        for (int i = 0; i < LEVEL_WEIGHTS.length; i++) {
            r -= LEVEL_WEIGHTS[i];
            if (r < 0) return i + 1;
        }
        return LEVEL_WEIGHTS.length;
    }
}
