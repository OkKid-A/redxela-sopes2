package com.redxela.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.redxela.exception.WarehouseFullException;

/**
 * Almacén físico: una cuadrícula de ubicaciones (pasillo, nivel, posición) de tamaño fijo.
 *
 * Asignación NO contigua (como paginación): un lote se reparte en las primeras ubicaciones
 * libres que se encuentren (first-fit por ubicación), aunque estén en pasillos distintos.
 * Cada lote conserva su tabla de ubicaciones (equivalente a la tabla de páginas de un proceso).
 * Todos los métodos son synchronized: el almacén es un recurso compartido entre el hilo de
 * Inventario (guarda) y los hilos de pedidos (retiran).
 */
@Component
public class Warehouse {
    public static final int AISLES = 6;
    public static final int LEVELS = 4;
    public static final int POSITIONS = 5;
    public static final int TOTAL_SLOTS = AISLES * LEVELS * POSITIONS;
    /** Unidades que caben en una ubicación. */
    public static final int SLOT_CAPACITY = 25;

    /** Lote recibido: sus ubicaciones como pares {ubicación, unidades}. */
    public static class Lot {
        final int id;
        final int productId;
        int units;
        final List<int[]> allocations = new ArrayList<>();

        Lot(int id, int productId) {
            this.id = id;
            this.productId = productId;
        }
    }

    /** Resultado de guardar: cuánto entró, cuánto no cupo y dónde quedó. */
    public record StoreResult(int lotId, int stored, int rejected, List<String> locations) {
    }

    private final int[] slotProduct = new int[TOTAL_SLOTS];
    private final int[] slotUnits = new int[TOTAL_SLOTS];
    private final int[] slotLot = new int[TOTAL_SLOTS];
    /** Lotes en orden de llegada (se consumen FIFO). */
    private final Map<Integer, Lot> lots = new LinkedHashMap<>();
    private int nextLotId = 1;

    public Warehouse() {
        Arrays.fill(slotProduct, -1);
    }

    /** Etiqueta legible de una ubicación, p. ej. "P2-N3-4" (pasillo 2, nivel 3, posición 4). */
    public static String label(int slot) {
        int aisle = slot / (LEVELS * POSITIONS);
        int level = (slot / POSITIONS) % LEVELS;
        int pos = slot % POSITIONS;
        return "P" + (aisle + 1) + "-N" + (level + 1) + "-" + (pos + 1);
    }

    /**
     * Guarda unidades de un producto en ubicaciones libres.
     * Si no cabe todo se guarda lo que quepa y se informa el sobrante en {@code rejected}.
     *
     * @throws WarehouseFullException si no hay ninguna ubicación libre
     */
    public synchronized StoreResult store(int productId, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("La cantidad debe ser mayor a cero");
        int free = freeSlots();
        if (free == 0) throw new WarehouseFullException("El almacén está lleno: no hay ubicaciones libres");

        int storable = Math.min(quantity, free * SLOT_CAPACITY);
        Lot lot = new Lot(nextLotId++, productId);
        lot.units = storable;
        List<String> locations = new ArrayList<>();
        int left = storable;
        for (int s = 0; s < TOTAL_SLOTS && left > 0; s++) {
            if (slotProduct[s] == -1) {
                int u = Math.min(SLOT_CAPACITY, left);
                slotProduct[s] = productId;
                slotUnits[s] = u;
                slotLot[s] = lot.id;
                lot.allocations.add(new int[] {s, u});
                locations.add(label(s) + "(" + u + ")");
                left -= u;
            }
        }
        lots.put(lot.id, lot);
        return new StoreResult(lot.id, storable, quantity - storable, locations);
    }

    /** Retira unidades de un producto (lotes más antiguos primero) y libera las ubicaciones que queden vacías. */
    public synchronized void remove(int productId, int quantity) {
        int left = quantity;
        for (Iterator<Lot> lotIt = lots.values().iterator(); lotIt.hasNext() && left > 0; ) {
            Lot lot = lotIt.next();
            if (lot.productId != productId) continue;
            for (Iterator<int[]> it = lot.allocations.iterator(); it.hasNext() && left > 0; ) {
                int[] a = it.next();
                int take = Math.min(a[1], left);
                a[1] -= take;
                lot.units -= take;
                slotUnits[a[0]] -= take;
                left -= take;
                if (a[1] == 0) {
                    slotProduct[a[0]] = -1;
                    slotLot[a[0]] = 0;
                    it.remove();
                }
            }
            if (lot.units == 0) lotIt.remove();
        }
        if (left > 0) {
            throw new IllegalStateException("Inconsistencia: faltaron " + left + " unidades del producto " + productId);
        }
    }

    public synchronized int freeSlots() {
        int n = 0;
        for (int p : slotProduct) if (p == -1) n++;
        return n;
    }

    /** Producto de cada ubicación (-1 = libre). */
    public synchronized int[] slotProducts() {
        return slotProduct.clone();
    }

    public synchronized int[] slotUnits() {
        return slotUnits.clone();
    }

    /** Lotes vigentes con sus ubicaciones, para mostrar la tabla de asignación. */
    public synchronized List<Map<String, Object>> lotsSnapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Lot lot : lots.values()) {
            List<String> locs = new ArrayList<>();
            for (int[] a : lot.allocations) locs.add(label(a[0]) + "(" + a[1] + ")");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lot", lot.id);
            m.put("productId", lot.productId);
            m.put("units", lot.units);
            m.put("locations", locs);
            out.add(m);
        }
        return out;
    }
}
