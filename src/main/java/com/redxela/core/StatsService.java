package com.redxela.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.redxela.model.Order;
import com.redxela.model.ServiceLevel;

/**
 * Estadísticas del sistema. Se llevan agregadas por política de planificación (para poder
 * compararlas) y por nivel de servicio. Los métodos son synchronized porque los escriben
 * muchos hilos trabajadores a la vez.
 */
@Component
public class StatsService {

    /** Acumulador de un grupo de pedidos completados. */
    private static class Agg {
        int count;
        long waitMs;
        long procMs;
        int late;
    }

    private final Map<SchedulingPolicy, Agg> byPolicy = new EnumMap<>(SchedulingPolicy.class);
    private final Map<ServiceLevel, Agg> byLevel = new EnumMap<>(ServiceLevel.class);
    private final AtomicInteger created = new AtomicInteger();

    public StatsService() {
        reset();
    }

    public void orderCreated() {
        created.incrementAndGet();
    }

    /** Registra un pedido completado. Espera = desde su creación hasta que empezó a procesarse. */
    public synchronized void record(Order o, long minuteMs) {
        long wait = o.startedAt - o.getCreatedAt();
        long proc = o.finishedAt - o.startedAt;
        boolean late = wait > o.targetWaitMs(minuteMs);
        for (Agg a : new Agg[] {byPolicy.get(o.policyUsed), byLevel.get(o.getLevel())}) {
            a.count++;
            a.waitMs += wait;
            a.procMs += proc;
            if (late) a.late++;
        }
    }

    public synchronized void reset() {
        created.set(0);
        for (SchedulingPolicy p : SchedulingPolicy.values()) byPolicy.put(p, new Agg());
        for (ServiceLevel l : ServiceLevel.values()) byLevel.put(l, new Agg());
    }

    public synchronized Map<String, Object> snapshot(long minuteMs) {
        int completed = 0;
        long wait = 0, proc = 0;
        int late = 0;
        for (Agg a : byLevel.values()) {
            completed += a.count;
            wait += a.waitMs;
            proc += a.procMs;
            late += a.late;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("created", created.get());
        m.put("completed", completed);
        m.put("late", late);
        m.put("avgWaitMin", avgMinutes(wait, completed, minuteMs));
        m.put("avgProcMin", avgMinutes(proc, completed, minuteMs));

        List<Map<String, Object>> policies = new ArrayList<>();
        for (SchedulingPolicy p : SchedulingPolicy.values()) {
            Agg a = byPolicy.get(p);
            policies.add(row(p.name(), p.getLabel(), a, minuteMs));
        }
        m.put("byPolicy", policies);

        List<Map<String, Object>> levels = new ArrayList<>();
        for (ServiceLevel l : ServiceLevel.values()) {
            levels.add(row(String.valueOf(l.getLevel()), l.getLevel() + " - " + l.getLabel(), byLevel.get(l), minuteMs));
        }
        m.put("byLevel", levels);
        return m;
    }

    private Map<String, Object> row(String key, String label, Agg a, long minuteMs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("key", key);
        r.put("label", label);
        r.put("count", a.count);
        r.put("avgWaitMin", avgMinutes(a.waitMs, a.count, minuteMs));
        r.put("avgProcMin", avgMinutes(a.procMs, a.count, minuteMs));
        r.put("late", a.late);
        return r;
    }

    /** Promedio en minutos simulados con un decimal. */
    private double avgMinutes(long totalMs, int count, long minuteMs) {
        if (count == 0) return 0;
        return Math.round(10.0 * totalMs / count / minuteMs) / 10.0;
    }
}
