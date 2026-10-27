package com.redxela.core;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.springframework.stereotype.Component;

/** Bitácora circular de eventos del simulador, visible en la interfaz. */
@Component
public class EventLog {
    private static final int MAX = 80;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Deque<String> events = new ArrayDeque<>();

    public synchronized void log(String message) {
        events.addFirst(LocalTime.now().format(FMT) + "  " + message);
        while (events.size() > MAX) events.removeLast();
    }

    /** Más recientes primero. */
    public synchronized List<String> recent() {
        return new ArrayList<>(events);
    }
}
