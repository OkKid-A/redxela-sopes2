package com.redxela.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.redxela.exception.RedxelaException;
import com.redxela.model.Customer;
import com.redxela.model.ServiceLevel;

/** Cuentas de clientes (en memoria). Se precargan algunas para poder simular de inmediato. */
@Component
public class CustomerRegistry {
    private final List<Customer> customers = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public CustomerRegistry() {
        String[] names = {"Comercial Xela", "Ferretería Occidente", "Tienda La Torre", "Distribuidora Atitlán",
                "Electro Quetzal", "Mercado Central", "Importadora Maya", "Super Zona 3", "Bodega El Sol", "Tecno Altiplano"};
        ServiceLevel[] levels = ServiceLevel.values();
        for (int i = 0; i < names.length; i++) {
            customers.add(new Customer(nextId.getAndIncrement(), names[i], levels[i % levels.length]));
        }
    }

    /** Crea una cuenta nueva con el nivel de servicio elegido. */
    public Customer create(String name, int level) {
        if (name == null || name.isBlank()) throw new RedxelaException("El nombre del cliente es obligatorio");
        Customer c = new Customer(nextId.getAndIncrement(), name.trim(), ServiceLevel.fromLevel(level));
        customers.add(c);
        return c;
    }

    public List<Customer> all() {
        return new ArrayList<>(customers);
    }

    public Optional<Customer> byId(int id) {
        return customers.stream().filter(c -> c.id() == id).findFirst();
    }

    public List<Customer> ofLevel(ServiceLevel level) {
        return customers.stream().filter(c -> c.level() == level).toList();
    }
}
