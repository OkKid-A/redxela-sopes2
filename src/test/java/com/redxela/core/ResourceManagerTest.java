package com.redxela.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.redxela.model.ResourceType;

class ResourceManagerTest {

    /** Corre una operación que pide dos recursos con una pausa en medio, para forzar el entrelazado. */
    private Thread operation(ResourceManager rm, String name, ResourceType first, ResourceType second) {
        Thread t = new Thread(() -> {
            try {
                rm.acquire(first, name);
                Thread.sleep(300);
                rm.acquire(second, name);
                rm.release(second, name);
                rm.release(first, name);
            } catch (InterruptedException e) {
                // víctima del deadlock: libera lo que retiene
                rm.release(first, name);
            }
        }, name);
        t.start();
        return t;
    }

    @Test
    void detectsDeadlockWithOppositeOrderAndResolvesItByInterrupt() throws Exception {
        ResourceManager rm = new ResourceManager();
        Thread a = operation(rm, "A", ResourceType.SCANNER, ResourceType.LOADING);
        Thread b = operation(rm, "B", ResourceType.LOADING, ResourceType.SCANNER);
        Thread c = operation(rm, "C", ResourceType.LOADING, ResourceType.SCANNER);

        Thread.sleep(800);
        ResourceManager.DeadlockInfo info = rm.detectDeadlock();
        assertNotNull(info, "debe detectarse el deadlock");
        assertEquals(3, info.participants().size());

        // A continúa; B y C son víctimas.
        b.interrupt();
        c.interrupt();
        a.join(3000);
        b.join(3000);
        c.join(3000);
        assertTrue(!a.isAlive() && !b.isAlive() && !c.isAlive(), "todos deben terminar tras resolver");
        assertNull(rm.detectDeadlock());
    }

    @Test
    void sameOrderNeverDeadlocks() throws Exception {
        ResourceManager rm = new ResourceManager();
        Thread[] ts = new Thread[6];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = operation(rm, "T" + i, ResourceType.SCANNER, ResourceType.LOADING);
        }
        for (Thread t : ts) t.join(10000);
        for (Thread t : ts) assertTrue(!t.isAlive());
        assertNull(rm.detectDeadlock());
    }
}
