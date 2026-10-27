package com.redxela.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

import org.springframework.stereotype.Component;

import com.redxela.model.ResourceType;

/**
 * Administra los recursos compartidos del centro.
 *
 * - Cada tipo de recurso es un {@link Semaphore} contador "fair" (FIFO): garantiza exclusión
 *   mutua sobre cada unidad y evita inanición entre hilos que esperan el mismo recurso.
 * - Java no registra quién tiene un permiso de un semáforo, así que este gestor mantiene su
 *   propia contabilidad (poseedores y esperando) protegida por un monitor. Con ella la
 *   interfaz muestra "en uso por quién" y se puede DETECTAR deadlock.
 */
@Component
public class ResourceManager {

    /** Operación involucrada en un deadlock. */
    public record Participant(String holder, List<ResourceType> held, ResourceType waitingFor,
                              List<String> blockedBy) {
    }

    /** Resultado de la detección de deadlock. */
    public record DeadlockInfo(List<Participant> participants, Set<ResourceType> resources) {
    }

    private final Map<ResourceType, Semaphore> semaphores = new EnumMap<>(ResourceType.class);
    /** Poseedores actuales de cada recurso (una entrada por unidad retenida). */
    private final Map<ResourceType, List<String>> holders = new EnumMap<>(ResourceType.class);
    /** Qué recurso espera cada operación bloqueada (una operación espera a lo sumo un recurso). */
    private final Map<String, ResourceType> waiting = new LinkedHashMap<>();
    /** Por recurso: {último cambio en nanos, integral de unidades-en-uso x nanos} para la ocupación. */
    private final Map<ResourceType, long[]> usage = new EnumMap<>(ResourceType.class);
    /** Monitor que protege holders, waiting y usage. */
    private final Object lock = new Object();
    private long statsStart = System.nanoTime();

    public ResourceManager() {
        long now = System.nanoTime();
        for (ResourceType t : ResourceType.values()) {
            semaphores.put(t, new Semaphore(t.getCapacity(), true));
            holders.put(t, new ArrayList<>());
            usage.put(t, new long[] {now, 0});
        }
    }

    /**
     * Toma una unidad del recurso; bloquea mientras no haya disponibles.
     *
     * @throws InterruptedException si el hilo es interrumpido mientras espera (así se rompe un deadlock)
     */
    public void acquire(ResourceType type, String holder) throws InterruptedException {
        synchronized (lock) {
            waiting.put(holder, type);
        }
        try {
            semaphores.get(type).acquire();
        } catch (InterruptedException e) {
            synchronized (lock) {
                waiting.remove(holder);
            }
            throw e;
        }
        synchronized (lock) {
            waiting.remove(holder);
            touch(type);
            holders.get(type).add(holder);
        }
    }

    /** Devuelve una unidad. La contabilidad se actualiza ANTES de liberar el permiso. */
    public void release(ResourceType type, String holder) {
        synchronized (lock) {
            touch(type);
            if (!holders.get(type).remove(holder)) {
                throw new IllegalStateException(holder + " intentó liberar " + type + " sin poseerlo");
            }
        }
        semaphores.get(type).release();
    }

    /** Acumula la integral de ocupación hasta ahora; llamar con el lock tomado, antes de cambiar holders. */
    private void touch(ResourceType type) {
        long now = System.nanoTime();
        long[] u = usage.get(type);
        u[1] += (long) holders.get(type).size() * (now - u[0]);
        u[0] = now;
    }

    /** Reinicia la medición de ocupación. */
    public void resetUsage() {
        synchronized (lock) {
            long now = System.nanoTime();
            statsStart = now;
            for (ResourceType t : ResourceType.values()) usage.put(t, new long[] {now, 0});
        }
    }

    /** Estado de cada recurso para la interfaz. */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (lock) {
            long now = System.nanoTime();
            for (ResourceType t : ResourceType.values()) {
                long[] u = usage.get(t);
                double integral = u[1] + (double) holders.get(t).size() * (now - u[0]);
                double total = (double) t.getCapacity() * (now - statsStart);
                List<String> waiters = new ArrayList<>();
                for (Map.Entry<String, ResourceType> e : waiting.entrySet()) {
                    if (e.getValue() == t) waiters.add(e.getKey());
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.name());
                m.put("label", t.getLabel());
                m.put("capacity", t.getCapacity());
                m.put("inUse", holders.get(t).size());
                m.put("available", t.getCapacity() - holders.get(t).size());
                m.put("holders", new ArrayList<>(holders.get(t)));
                m.put("waiters", waiters);
                m.put("utilization", total <= 0 ? 0 : Math.round(1000 * integral / total) / 10.0);
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Detecta deadlock con el algoritmo de detección para recursos de varias instancias
     * (el de las matrices Asignación/Solicitud/Disponible):
     * 1. Disponible = capacidad - retenido. Una operación puede "terminar" si no espera nada
     *    o lo que espera está disponible; al terminar devuelve lo que retiene.
     * 2. Se repite hasta que nadie más pueda terminar. Las que retienen recursos y no pudieron
     *    terminar están en deadlock.
     * 3. Se descartan las que solo están bloqueadas INDIRECTAMENTE (retienen algo que nadie del
     *    grupo necesita): no forman parte del ciclo.
     *
     * @return null si no hay deadlock
     */
    public DeadlockInfo detectDeadlock() {
        synchronized (lock) {
            Map<String, List<ResourceType>> held = new HashMap<>();
            Map<ResourceType, Integer> work = new EnumMap<>(ResourceType.class);
            for (ResourceType t : ResourceType.values()) {
                work.put(t, t.getCapacity() - holders.get(t).size());
                for (String h : holders.get(t)) held.computeIfAbsent(h, k -> new ArrayList<>()).add(t);
            }

            // Solo cuentan las operaciones que retienen algo (las demás no pueden estar en un ciclo).
            Set<String> unfinished = new LinkedHashSet<>(held.keySet());
            boolean progress = true;
            while (progress) {
                progress = false;
                for (Iterator<String> it = unfinished.iterator(); it.hasNext(); ) {
                    String p = it.next();
                    ResourceType req = waiting.get(p);
                    if (req == null || work.get(req) >= 1) {
                        for (ResourceType h : held.get(p)) work.merge(h, 1, Integer::sum);
                        it.remove();
                        progress = true;
                    }
                }
            }

            // Poda: quitar las operaciones cuyos recursos no espera nadie del grupo.
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Iterator<String> it = unfinished.iterator(); it.hasNext(); ) {
                    String p = it.next();
                    boolean needed = false;
                    for (String q : unfinished) {
                        ResourceType rq = waiting.get(q);
                        if (!q.equals(p) && rq != null && held.get(p).contains(rq)) {
                            needed = true;
                            break;
                        }
                    }
                    if (!needed) {
                        it.remove();
                        changed = true;
                    }
                }
            }
            if (unfinished.size() < 2) return null;

            List<Participant> participants = new ArrayList<>();
            Set<ResourceType> involved = new LinkedHashSet<>();
            for (String p : unfinished) {
                ResourceType req = waiting.get(p);
                List<String> blockers = new ArrayList<>();
                for (String q : unfinished) {
                    if (!q.equals(p) && req != null && held.get(q).contains(req)) blockers.add(q);
                }
                participants.add(new Participant(p, new ArrayList<>(held.get(p)), req, blockers));
                involved.addAll(held.get(p));
                if (req != null) involved.add(req);
            }
            return new DeadlockInfo(participants, involved);
        }
    }
}
