package com.redxela.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.redxela.core.ResourceManager.DeadlockInfo;
import com.redxela.core.ResourceManager.Participant;
import com.redxela.exception.RedxelaException;
import com.redxela.model.Customer;
import com.redxela.model.MerchandiseType;
import com.redxela.model.Order;
import com.redxela.model.OrderItem;
import com.redxela.model.OrderStatus;
import com.redxela.model.Product;
import com.redxela.model.ResourceType;
import com.redxela.model.ServiceLevel;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Núcleo del simulador: planificador + hilos trabajadores.
 *
 * - Lista de espera: pedidos que aún no tienen hilo. El hilo "planificador" elige de ahí el
 *   siguiente según la política activa (planificación).
 * - Pool de hilos trabajadores acotado: es el límite de pedidos que se procesan en paralelo.
 *   Un Semaphore (workerSlots) cuenta los trabajadores libres.
 * - Cada trabajador pide los recursos de su pedido de uno en uno y los RETIENE mientras pide
 *   los siguientes (hold-and-wait). Con la prevención activada los pide en el orden global fijo
 *   de {@link ResourceType}; desactivada, los pide en el orden natural del tipo de mercancía y
 *   puede formarse una espera circular (deadlock).
 */
@Component
public class DistributionCenter {
    /** Pausa entre la toma de un recurso y la del siguiente (tiempo de desplazamiento simulado). */
    private static final long THINK_SAFE_MS = 150;
    private static final long THINK_UNSAFE_MS = 1500;
    /** Enfriamiento de un pedido que fue víctima de un deadlock antes de poder reintentar. */
    private static final long COOLDOWN_MS = 5000;
    private static final long WORK_MS_PER_UNIT = 40;

    private final ResourceManager resources;
    private final InventoryControl inventory;
    private final Warehouse warehouse;
    private final ReceptionArea reception;
    private final StatsService stats;
    private final EventLog log;
    private final CustomerRegistry customers;
    private final Catalog catalog;
    private final long minuteMs;
    private final int workerCount;

    private final AtomicInteger nextId = new AtomicInteger(1);
    /** Lista de espera; todo acceso se hace sincronizando sobre ella. */
    private final List<Order> waiting = new ArrayList<>();
    private final Map<Integer, Order> active = new ConcurrentHashMap<>();
    private final Object wakeLock = new Object();

    private volatile SchedulingPolicy policy = SchedulingPolicy.PRIORIDAD_ESTRICTA;
    private volatile boolean prevention = true;
    private ExecutorService workerPool;
    private Semaphore workerSlots;
    private Thread dispatcher;

    public DistributionCenter(ResourceManager resources, InventoryControl inventory, Warehouse warehouse,
                              ReceptionArea reception,
                              StatsService stats, EventLog log, CustomerRegistry customers, Catalog catalog,
                              @Value("${redxela.sim.minute-ms:1000}") long minuteMs,
                              @Value("${redxela.workers:12}") int workerCount) {
        this.resources = resources;
        this.inventory = inventory;
        this.warehouse = warehouse;
        this.reception = reception;
        this.stats = stats;
        this.log = log;
        this.customers = customers;
        this.catalog = catalog;
        this.minuteMs = minuteMs;
        this.workerCount = workerCount;
    }

    @PostConstruct
    void start() {
        workerSlots = new Semaphore(workerCount);
        workerPool = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "trabajador");
            t.setDaemon(true);
            return t;
        });
        inventory.setStockListener(this::wake);
        dispatcher = new Thread(this::dispatchLoop, "planificador");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    @PreDestroy
    void stop() {
        if (dispatcher != null) dispatcher.interrupt();
        workerPool.shutdownNow();
    }

    // ---------------------------------------------------------------- configuración

    public SchedulingPolicy getPolicy() { return policy; }

    public void setPolicy(SchedulingPolicy p) {
        this.policy = p;
        log.log("CONFIG: política de planificación = " + p.getLabel());
        wake();
    }

    public boolean isPrevention() { return prevention; }

    public void setPrevention(boolean enabled) {
        this.prevention = enabled;
        log.log("CONFIG: prevención de deadlock " + (enabled ? "ACTIVADA (orden fijo de recursos)" : "DESACTIVADA (orden natural, puede haber deadlock)"));
    }

    public long getMinuteMs() { return minuteMs; }

    // ---------------------------------------------------------------- pedidos

    /** Solicitud de una línea de pedido. */
    public record ItemRequest(int productId, int qty) {
    }

    /** Valida y registra un pedido nuevo en la lista de espera. */
    public Order createOrder(int customerId, List<ItemRequest> requests) {
        Customer customer = customers.byId(customerId)
                .orElseThrow(() -> new RedxelaException("Cliente inexistente: " + customerId));
        if (requests == null || requests.isEmpty()) throw new RedxelaException("El pedido debe tener al menos un producto");
        List<OrderItem> items = new ArrayList<>();
        for (ItemRequest r : requests) {
            Product p = catalog.byId(r.productId())
                    .orElseThrow(() -> new RedxelaException("Producto inexistente: " + r.productId()));
            if (r.qty() <= 0) throw new RedxelaException("La cantidad de " + p.name() + " debe ser mayor a cero");
            if (r.qty() > Warehouse.TOTAL_SLOTS * Warehouse.SLOT_CAPACITY) {
                throw new RedxelaException("La cantidad de " + p.name() + " excede la capacidad total del almacén");
            }
            items.add(new OrderItem(p, r.qty()));
        }
        Order o = new Order(nextId.getAndIncrement(), customer, items);
        synchronized (waiting) {
            waiting.add(o);
        }
        stats.orderCreated();
        log.log("PEDIDO " + o.holderName() + " de " + customer.name() + " (nivel " + o.getLevel().getLevel()
                + ", " + o.getTotalUnits() + " uds) entra a la lista de espera");
        wake();
        return o;
    }

    private void wake() {
        synchronized (wakeLock) {
            wakeLock.notifyAll();
        }
    }

    // ---------------------------------------------------------------- planificador

    /** Hilo planificador: espera un trabajador libre y le asigna el mejor pedido según la política. */
    private void dispatchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                workerSlots.acquire();
                Order next = null;
                while (next == null) {
                    next = pickNext();
                    if (next == null) {
                        synchronized (wakeLock) {
                            // Espera acotada: re-evalúa periódicamente (el envejecimiento cambia con el tiempo).
                            wakeLock.wait(200);
                        }
                    }
                }
                Order chosen = next;
                try {
                    workerPool.submit(() -> process(chosen));
                } catch (RuntimeException e) {
                    workerSlots.release();
                    throw e;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.log("PLANIFICADOR: error " + e.getMessage());
            }
        }
    }

    /**
     * Elige el siguiente pedido: el mejor según la política entre los que pueden reservar sus
     * existencias. Los que no tienen stock se marcan WAITING_STOCK y se saltan (no bloquean a los demás).
     */
    private Order pickNext() {
        long now = System.currentTimeMillis();
        synchronized (waiting) {
            List<Order> candidates = new ArrayList<>(waiting);
            candidates.sort(policy.comparator(now, minuteMs));
            for (Order o : candidates) {
                if (o.notBefore > now) {
                    o.status = OrderStatus.WAITING;
                    continue;
                }
                if (inventory.tryReserve(o)) {
                    waiting.remove(o);
                    o.status = OrderStatus.ACQUIRING;
                    return o;
                }
                o.status = OrderStatus.WAITING_STOCK;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- trabajador

    /** Procesa un pedido en un hilo trabajador: adquirir recursos, trabajar, liberar. */
    private void process(Order o) {
        String holder = o.holderName();
        o.worker = Thread.currentThread();
        o.policyUsed = policy;
        active.put(o.getId(), o);
        boolean completed = false;
        boolean aborted = false;
        try {
            List<ResourceType> sequence = o.requiredSequence();
            if (prevention) {
                // PREVENCIÓN: orden global fijo => no puede haber espera circular.
                sequence.sort(Comparator.comparingInt(ResourceType::ordinal));
            }
            long think = prevention ? THINK_SAFE_MS : THINK_UNSAFE_MS;
            for (ResourceType type : sequence) {
                o.waitingFor = type;
                resources.acquire(type, holder);
                o.held.add(type);
                o.waitingFor = null;
                Thread.sleep(think);
            }

            o.workTotalMs = Math.max(1500, Math.min(8000, o.getTotalUnits() * WORK_MS_PER_UNIT));
            o.workDoneMs = 0;
            o.startedAt = System.currentTimeMillis();
            o.status = OrderStatus.PROCESSING;
            while (o.workDoneMs < o.workTotalMs) {
                Thread.sleep(100);
                o.workDoneMs += 100;
                o.progress = (int) Math.min(100, 100 * o.workDoneMs / o.workTotalMs);
            }
            inventory.commit(o);
            o.progress = 100;
            o.finishedAt = System.currentTimeMillis();
            o.status = OrderStatus.COMPLETED;
            completed = true;
        } catch (InterruptedException e) {
            aborted = true; // nos eligieron como víctima de un deadlock
        } catch (RuntimeException e) {
            aborted = true;
            log.log("ERROR en " + holder + ": " + e.getMessage() + " (vuelve a la lista de espera)");
        } finally {
            releaseAll(o);
            o.worker = null;
            o.waitingFor = null;
            active.remove(o.getId());
            Thread.interrupted(); // limpia una posible interrupción pendiente: el hilo se reutiliza
            workerSlots.release();
        }

        if (completed) {
            stats.record(o, minuteMs);
            log.log("PEDIDO " + holder + " COMPLETADO (esperó " + Math.round((o.startedAt - o.getCreatedAt()) / (double) minuteMs) + " min sim)");
        } else if (aborted) {
            requeue(o);
        }
        wake();
    }

    /** Suelta los recursos en orden inverso al de adquisición. */
    private void releaseAll(Order o) {
        List<ResourceType> toRelease = new ArrayList<>(o.held);
        for (int i = toRelease.size() - 1; i >= 0; i--) {
            resources.release(toRelease.get(i), o.holderName());
            o.held.remove(toRelease.get(i));
        }
    }

    /** Devuelve un pedido abortado a la lista de espera, con enfriamiento para no reincidir al instante. */
    private void requeue(Order o) {
        inventory.release(o);
        o.retries++;
        o.progress = 0;
        o.workDoneMs = 0;
        o.status = OrderStatus.WAITING;
        o.notBefore = System.currentTimeMillis() + COOLDOWN_MS;
        synchronized (waiting) {
            waiting.add(o);
        }
        log.log("PEDIDO " + o.holderName() + " liberó sus recursos y regresó a la lista de espera");
    }

    // ---------------------------------------------------------------- deadlock

    /**
     * Prepara a propósito un escenario de deadlock: desactiva la prevención y crea tres pedidos
     * Exprés: un ELECTRONICO (pide escáner y luego área de carga) y dos PERECEDERO (piden área
     * de carga y luego escáner). Hay 2 áreas de carga y 1 escáner: cada operación retiene lo que
     * la otra necesita.
     */
    public void provokeDeadlock() {
        Customer c = customers.ofLevel(ServiceLevel.EXPRES).stream().findFirst()
                .orElseThrow(() -> new RedxelaException("No hay clientes de nivel Exprés"));
        // Se elige el producto con más existencias de cada tipo; sin stock los pedidos esperarían
        // existencias en lugar de competir por los recursos y el escenario no se formaría.
        Product electronic = mostStocked(MerchandiseType.ELECTRONICO, 5);
        Product perishable = mostStocked(MerchandiseType.PERECEDERO, 10);
        setPrevention(false);
        createOrder(c.id(), List.of(new ItemRequest(electronic.id(), 5)));
        createOrder(c.id(), List.of(new ItemRequest(perishable.id(), 5)));
        createOrder(c.id(), List.of(new ItemRequest(perishable.id(), 5)));
        log.log("DEADLOCK: escenario provocado (3 pedidos Exprés con recursos en orden inverso)");
    }

    /** Producto del tipo con más existencias; error claro si ninguno alcanza el mínimo. */
    private Product mostStocked(MerchandiseType type, int minimum) {
        Product best = catalog.ofType(type).stream()
                .max(Comparator.comparingInt(p -> inventory.availableOf(p.id()))).orElseThrow();
        if (inventory.availableOf(best.id()) < minimum) {
            throw new RedxelaException("No hay existencias para provocar el deadlock: registre una llegada de "
                    + best.name() + " (se necesitan al menos " + minimum + " unidades)");
        }
        return best;
    }

    /** Deadlock vigente (o null). */
    public DeadlockInfo currentDeadlock() {
        return resources.detectDeadlock();
    }

    /**
     * Resuelve el deadlock: el pedido elegido sigue; los demás involucrados reciben una
     * interrupción, sueltan sus recursos y regresan a la lista de espera.
     */
    public String resolveDeadlock(String winner) {
        DeadlockInfo info = resources.detectDeadlock();
        if (info == null) throw new IllegalStateException("No hay ningún deadlock activo");
        boolean valid = info.participants().stream().anyMatch(p -> p.holder().equals(winner));
        if (!valid) throw new RedxelaException(winner + " no forma parte del deadlock actual");

        List<String> victims = new ArrayList<>();
        for (Participant p : info.participants()) {
            if (p.holder().equals(winner)) continue;
            Order o = active.get(Order.idFromHolder(p.holder()));
            Thread t = o == null ? null : o.worker;
            if (t != null) {
                t.interrupt(); // acquire() lanza InterruptedException y el trabajador aborta limpiamente
                victims.add(p.holder());
            }
        }
        String msg = "DEADLOCK resuelto: continúa " + winner + "; regresan a la lista de espera " + String.join(", ", victims);
        log.log(msg);
        return msg;
    }

    // ---------------------------------------------------------------- vista para la interfaz

    /** Foto del estado del sistema que consume la interfaz. */
    public Map<String, Object> state() {
        long now = System.currentTimeMillis();
        Map<String, Object> s = new LinkedHashMap<>();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("policy", policy.name());
        config.put("prevention", prevention);
        config.put("minuteMs", minuteMs);
        config.put("workers", workerCount);
        config.put("activeWorkers", active.size());
        s.put("config", config);

        s.put("resources", resources.snapshot());

        List<Map<String, Object>> processing = new ArrayList<>();
        List<Order> act = new ArrayList<>(active.values());
        act.sort(Comparator.comparingInt(Order::getId));
        for (Order o : act) {
            Map<String, Object> m = orderBase(o);
            m.put("status", o.status.name());
            m.put("progress", o.progress);
            m.put("etaMs", o.status == OrderStatus.PROCESSING ? Math.max(0, o.workTotalMs - o.workDoneMs) : -1);
            m.put("held", o.held.stream().map(Enum::name).toList());
            ResourceType wf = o.waitingFor;
            m.put("waitingFor", wf == null ? null : wf.name());
            processing.add(m);
        }
        s.put("processing", processing);

        List<Map<String, Object>> waitingView = new ArrayList<>();
        synchronized (waiting) {
            List<Order> sorted = new ArrayList<>(waiting);
            SchedulingPolicy p = policy;
            sorted.sort(p.comparator(now, minuteMs));
            int pos = 1;
            for (Order o : sorted) {
                Map<String, Object> m = orderBase(o);
                m.put("pos", pos++);
                m.put("status", o.status.name());
                m.put("sortKey", p.describeKey(o, now, minuteMs));
                m.put("waitedMin", Math.round(10.0 * (now - o.getCreatedAt()) / minuteMs) / 10.0);
                m.put("targetMin", o.targetWaitMs(minuteMs) / (double) minuteMs);
                m.put("late", now - o.getCreatedAt() > o.targetWaitMs(minuteMs));
                String reason;
                if (o.notBefore > now) {
                    reason = "Reintento en " + Math.round((o.notBefore - now) / 1000.0) + " s (enfriamiento tras deadlock)";
                } else if (o.status == OrderStatus.WAITING_STOCK) {
                    reason = "Sin existencias: " + inventory.missingFor(o);
                } else {
                    reason = "Esperando turno según la política";
                }
                m.put("reason", reason);
                waitingView.add(m);
            }
        }
        s.put("waiting", waitingView);

        s.put("stats", stats.snapshot(minuteMs));

        DeadlockInfo d = resources.detectDeadlock();
        s.put("deadlock", d == null ? null : deadlockView(d));

        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("products", inventory.stockSnapshot());
        inv.put("slotProducts", warehouse.slotProducts());
        inv.put("slotUnits", warehouse.slotUnits());
        inv.put("freeSlots", warehouse.freeSlots());
        inv.put("totalSlots", Warehouse.TOTAL_SLOTS);
        inv.put("lots", warehouse.lotsSnapshot());
        inv.put("mailboxSize", inventory.mailboxSize());
        inv.put("mailboxCapacity", InventoryControl.MAILBOX_CAPACITY);
        inv.put("trucksWaiting", reception.trucksWaiting());
        inv.put("messagesSent", reception.messagesSent());
        s.put("inventory", inv);

        s.put("events", log.recent());
        return s;
    }

    private Map<String, Object> orderBase(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("holder", o.holderName());
        m.put("customer", o.getCustomer().name());
        m.put("level", o.getLevel().getLevel());
        m.put("levelLabel", o.getLevel().getLabel());
        m.put("units", o.getTotalUnits());
        m.put("retries", o.retries);
        m.put("types", o.types().stream().map(MerchandiseType::getLabel).toList());
        List<String> items = new ArrayList<>();
        for (OrderItem it : o.getItems()) items.add(it.quantity() + " x " + it.product().name());
        m.put("items", items);
        return m;
    }

    private Map<String, Object> deadlockView(DeadlockInfo d) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> parts = new ArrayList<>();
        StringBuilder why = new StringBuilder();
        for (Participant p : d.participants()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("holder", p.holder());
            pm.put("held", p.held().stream().map(Enum::name).toList());
            pm.put("waitingFor", p.waitingFor() == null ? null : p.waitingFor().name());
            pm.put("blockedBy", p.blockedBy());
            parts.add(pm);
            why.append(p.holder()).append(" retiene [")
                    .append(String.join(", ", p.held().stream().map(ResourceType::getLabel).toList()))
                    .append("] y espera [").append(p.waitingFor() == null ? "-" : p.waitingFor().getLabel())
                    .append("] que tienen ").append(String.join(", ", p.blockedBy())).append(". ");
        }
        why.append("Espera circular: cada operación necesita un recurso que otra del grupo retiene y ninguna lo soltará, "
                + "por lo que ninguna puede avanzar sin intervención.");
        m.put("participants", parts);
        m.put("resources", d.resources().stream().map(Enum::name).toList());
        m.put("explanation", why.toString());
        return m;
    }
}
