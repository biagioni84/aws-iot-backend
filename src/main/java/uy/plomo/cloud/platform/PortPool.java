package uy.plomo.cloud.platform;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.HashMap;

@Component
public class PortPool {

    public enum PortStatus {
        FREE, IN_USE
    }

    public record PortEntry(String user, PortStatus status) {}

    private final Map<Integer, PortEntry> portMap = new HashMap<>();

    /**
     * Initialize the pool with a range of ports [rangeStart, rangeEnd] inclusive.
     */
    public PortPool(int rangeStart, int rangeEnd) {
        for (int port = rangeStart; port <= rangeEnd; port++) {
            portMap.put(port, new PortEntry(null, PortStatus.FREE));
        }
    }

    public synchronized int acquirePort(String user, int port) {
        PortEntry entry = portMap.get(port);
        if (entry == null) {
            throw new IllegalArgumentException("Port " + port + " is not managed by this pool");
        }
        // Idempotente: si el mismo usuario ya tiene el puerto, devolver sin error.
        if (entry.status() == PortStatus.IN_USE && user.equals(entry.user())) {
            return port;
        }
        if (entry.status() != PortStatus.FREE) {
            throw new IllegalStateException("Port " + port + " is already in use by: " + entry.user());
        }
        portMap.put(port, new PortEntry(user, PortStatus.IN_USE));
        return port;
    }
    /**
     * Release a port back to the pool, marking it as FREE.
     */
    public synchronized void releasePort(int port) {
        PortEntry entry = portMap.get(port);
        if (entry == null) {
            throw new IllegalArgumentException("Port " + port + " is not managed by this pool");
        }
        portMap.put(port, new PortEntry(null, PortStatus.FREE));
    }

    /**
     * Get the current state of a specific port.
     */
    public PortEntry getPortEntry(int port) {
        return portMap.get(port);
    }

    /**
     * Get a snapshot of all ports and their current state.
     */
    public Map<Integer, PortEntry> getAllPorts() {
        return Collections.unmodifiableMap(portMap);
    }

    /**
     * Get all ports currently in use.
     */
    public Map<Integer, PortEntry> getUsedPorts() {
        Map<Integer, PortEntry> used = new TreeMap<>();
        portMap.forEach((port, entry) -> {
            if (entry.status() == PortStatus.IN_USE) used.put(port, entry);
        });
        return used;
    }

    /**
     * Get all free ports.
     */
    public List<Integer> getFreePorts() {
        return portMap.entrySet().stream()
                .filter(e -> e.getValue().status() == PortStatus.FREE)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}