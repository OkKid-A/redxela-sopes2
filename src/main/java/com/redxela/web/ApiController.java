package com.redxela.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.redxela.core.Catalog;
import com.redxela.core.CustomerRegistry;
import com.redxela.core.DistributionCenter;
import com.redxela.core.DistributionCenter.ItemRequest;
import com.redxela.core.OrderGenerator;
import com.redxela.core.ReceptionArea;
import com.redxela.core.ResourceManager;
import com.redxela.core.SchedulingPolicy;
import com.redxela.core.StatsService;
import com.redxela.exception.RedxelaException;
import com.redxela.model.Customer;
import com.redxela.model.MerchandiseType;
import com.redxela.model.Order;
import com.redxela.model.Product;
import com.redxela.model.ResourceType;
import com.redxela.model.ServiceLevel;

/** API REST que consume la interfaz web. */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final DistributionCenter center;
    private final OrderGenerator generator;
    private final ReceptionArea reception;
    private final CustomerRegistry customers;
    private final Catalog catalog;
    private final StatsService stats;
    private final ResourceManager resources;

    public ApiController(DistributionCenter center, OrderGenerator generator, ReceptionArea reception,
                         CustomerRegistry customers, Catalog catalog, StatsService stats, ResourceManager resources) {
        this.center = center;
        this.generator = generator;
        this.reception = reception;
        this.customers = customers;
        this.catalog = catalog;
        this.stats = stats;
        this.resources = resources;
    }

    // ---- solicitudes (JSON -> record) ----
    public record OrderRequest(int customerId, List<ItemRequest> items) { }
    public record CustomerRequest(String name, int level) { }
    public record ArrivalRequest(int productId, int qty) { }
    public record GeneratorRequest(boolean running, int intervalMs, boolean autoArrivals) { }
    public record ConfigRequest(String policy, Boolean prevention) { }
    public record ResolveRequest(String winner) { }

    /** Foto completa del sistema; la interfaz la consulta cada ~500 ms. */
    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> s = center.state();
        s.put("generator", generator.status());
        return s;
    }

    /** Datos estáticos para armar formularios y textos. */
    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> products = new ArrayList<>();
        for (Product p : catalog.all()) {
            products.add(Map.of("id", p.id(), "name", p.name(), "type", p.type().name(), "typeLabel", p.type().getLabel()));
        }
        m.put("products", products);

        List<Map<String, Object>> types = new ArrayList<>();
        for (MerchandiseType t : MerchandiseType.values()) {
            types.add(Map.of("id", t.name(), "label", t.getLabel(), "description", t.getDescription(),
                    "resources", t.getSequence().stream().map(Enum::name).toList()));
        }
        m.put("types", types);

        List<Map<String, Object>> levels = new ArrayList<>();
        for (ServiceLevel l : ServiceLevel.values()) {
            levels.add(Map.of("level", l.getLevel(), "label", l.getLabel(), "maxWaitMinutes", l.getMaxWaitMinutes()));
        }
        m.put("levels", levels);

        List<Map<String, Object>> res = new ArrayList<>();
        for (ResourceType r : ResourceType.values()) {
            res.add(Map.of("id", r.name(), "label", r.getLabel(), "capacity", r.getCapacity()));
        }
        m.put("resources", res);

        List<Map<String, Object>> pol = new ArrayList<>();
        for (SchedulingPolicy p : SchedulingPolicy.values()) {
            pol.add(Map.of("id", p.name(), "label", p.getLabel(), "description", p.getDescription()));
        }
        m.put("policies", pol);
        return m;
    }

    @GetMapping("/customers")
    public List<Customer> customers() {
        return customers.all();
    }

    @PostMapping("/customers")
    public Customer createCustomer(@RequestBody CustomerRequest req) {
        return customers.create(req.name(), req.level());
    }

    @PostMapping("/orders")
    public Map<String, Object> createOrder(@RequestBody OrderRequest req) {
        Order o = center.createOrder(req.customerId(), req.items());
        return Map.of("id", o.getId(), "holder", o.holderName());
    }

    @PostMapping("/arrivals")
    public Map<String, Object> arrival(@RequestBody ArrivalRequest req) {
        reception.registerArrival(req.productId(), req.qty());
        return Map.of("ok", true);
    }

    @PostMapping("/generator")
    public Map<String, Object> generator(@RequestBody GeneratorRequest req) {
        if (req.running()) generator.start(req.intervalMs(), req.autoArrivals());
        else generator.stop();
        return generator.status();
    }

    @PostMapping("/config")
    public Map<String, Object> config(@RequestBody ConfigRequest req) {
        if (req.policy() != null) {
            try {
                center.setPolicy(SchedulingPolicy.valueOf(req.policy()));
            } catch (IllegalArgumentException e) {
                throw new RedxelaException("Política desconocida: " + req.policy());
            }
        }
        if (req.prevention() != null) center.setPrevention(req.prevention());
        return Map.of("policy", center.getPolicy().name(), "prevention", center.isPrevention());
    }

    @PostMapping("/deadlock/provoke")
    public Map<String, Object> provoke() {
        generator.stop(); // sistema más tranquilo = escenario reproducible
        center.provokeDeadlock();
        return Map.of("ok", true);
    }

    @PostMapping("/deadlock/resolve")
    public Map<String, Object> resolve(@RequestBody ResolveRequest req) {
        return Map.of("message", center.resolveDeadlock(req.winner()));
    }

    @PostMapping("/stats/reset")
    public Map<String, Object> resetStats() {
        stats.reset();
        resources.resetUsage();
        return Map.of("ok", true);
    }

    // ---- manejo de excepciones: siempre se responde JSON {error: "..."} ----

    @ExceptionHandler({RedxelaException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
