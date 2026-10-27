package com.redxela.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.redxela.exception.WarehouseFullException;

class WarehouseTest {

    @Test
    void lotCanBeSplitAcrossNonContiguousSlots() {
        Warehouse w = new Warehouse();
        w.store(1, 25);              // ocupa la ubicación 0
        w.store(2, 25);              // ubicación 1
        w.store(3, 25);              // ubicación 2
        w.remove(2, 25);             // libera la 1: queda un hueco entre 0 y 2
        Warehouse.StoreResult r = w.store(4, 50);  // necesita 2 ubicaciones: la 1 y la 3
        assertEquals(2, r.locations().size());
        assertEquals(50, r.stored());
        assertTrue(r.locations().get(0).startsWith(Warehouse.label(1)));
        assertTrue(r.locations().get(1).startsWith(Warehouse.label(3)));
    }

    @Test
    void storesWhatFitsAndReportsTheRest() {
        Warehouse w = new Warehouse();
        int capacity = Warehouse.TOTAL_SLOTS * Warehouse.SLOT_CAPACITY;
        Warehouse.StoreResult r = w.store(1, capacity + 10);
        assertEquals(capacity, r.stored());
        assertEquals(10, r.rejected());
        assertThrows(WarehouseFullException.class, () -> w.store(1, 1));
    }

    @Test
    void removeFreesSlots() {
        Warehouse w = new Warehouse();
        w.store(1, 60);
        assertEquals(Warehouse.TOTAL_SLOTS - 3, w.freeSlots());
        w.remove(1, 60);
        assertEquals(Warehouse.TOTAL_SLOTS, w.freeSlots());
    }
}
