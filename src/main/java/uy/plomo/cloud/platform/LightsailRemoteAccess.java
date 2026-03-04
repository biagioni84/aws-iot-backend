package uy.plomo.cloud.platform;


import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;
import org.apache.logging.log4j.CloseableThreadContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class LightsailRemoteAccess {
    public record ShellResult(int exit, String out, String err) {}
    // 1. Keep the field static
    public static String instanceName;

    // 2. Remove @Value from the field and put it on a NON-STATIC setter
    @Value("${iot.instanceName}")
    public void setInstanceName(String name) {
        instanceName = name;
    }

    public static ShellResult sh(String command) {
        return sh(command, 30, TimeUnit.SECONDS);
    }

    public static ShellResult sh(String command, long timeout, TimeUnit unit) {
        String executionId = UUID.randomUUID().toString().substring(0, 8);

        // Usamos CloseableThreadContext para que se limpie SOLO al salir del bloque
        try (final CloseableThreadContext.Instance ctc = CloseableThreadContext.put("shell_id", executionId)) {

            long startTime = System.currentTimeMillis();
            log.info("Ejecutando: [{}]", command);

            Process proc = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                pb.redirectErrorStream(true);
                proc = pb.start();

                // Cerramos stdin para evitar bloqueos interactivos
                proc.getOutputStream().close();

                boolean finished = proc.waitFor(timeout, unit);
                long duration = System.currentTimeMillis() - startTime;

                if (!finished) {
                    log.error("TIMEOUT tras {}ms. Posible comando interactivo.", duration);
                    proc.destroyForcibly();
                    return new ShellResult(-1, "", "Timeout");
                }

                String output = new String(proc.getInputStream().readAllBytes()).trim();
                log.info("Finalizado en {}ms. Exit: {}", duration, proc.exitValue());

                return new ShellResult(proc.exitValue(), output, "");

            } catch (Exception e) {
                log.error("Error en ejecución: {}", e.getMessage());
                return new ShellResult(-1, "", e.getMessage());
            } finally {
                if (proc != null && proc.isAlive()) proc.destroy();
            }
        } // Aquí el 'shell_id' se elimina automáticamente del hilo
    }
    // -----------------------------------------------------------------------
    // Config helper (mirrors (get @config :instance-name))
    // -----------------------------------------------------------------------

    private static String getInstanceName() {
        return instanceName;
    }





    // =======================================================================
    // Network functions
    // =======================================================================

    /**
     * Opens an inbound TCP port on AWS Lightsail for the given CIDR.
     * Mirrors: (add-inbound-rule port cidr)
     */
    public static ShellResult addInboundRule(String port, String cidr) {
        String cmd = String.format(
                "aws lightsail open-instance-public-ports --instance-name %s " +
                        "--port-info fromPort=%s,protocol=TCP,toPort=%s,cidrs=%s",
                getInstanceName(), port, port, cidr);
        return sh(cmd);
    }

    /**
     * Closes an inbound TCP port on AWS Lightsail.
     * Mirrors: (remove-inbound-rule port cidr)
     */
    public static ShellResult removeInboundRule(String port, String cidr) {
        String cmd = String.format(
                "aws lightsail close-instance-public-ports --instance-name %s " +
                        "--port-info fromPort=%s,protocol=TCP,toPort=%s,cidrs=%s",
                getInstanceName(), port, port, cidr);
        return sh(cmd);
    }

    /**
     * Reads the first line of ~/.ssh/authorized_keys for a user and parses it.
     * Returns a map with keys "ports" (List<String>) and "key" (String),
     * or null when the file is empty / unreadable.
     *
     * Mirrors: (authorized-keys-data user)
     */
    public static Map<String, Object> authorizedKeysData(String user) {
        String cmd = "sudo cat /home/" + user + "/.ssh/authorized_keys";
        ShellResult result = sh(cmd);
        String content = result.out().split("\n")[0];   // first line only

        if (content == null || content.isBlank()) return null;

        String[] parts = content.split(",");

        // permitopen="host:port" entries
        List<String> ports = Arrays.stream(parts)
                .filter(p -> p.matches("permitopen=.*"))
                .map(p -> p.split(":")[1].replace("\"", ""))
                .collect(Collectors.toList());

        // ssh-rsa key entry
        String rsaPart = Arrays.stream(parts)
                .filter(p -> p.matches("(.*)ssh-rsa(.*)"))
                .findFirst().orElse(null);

        String key = null;
        if (rsaPart != null) {
            String[] keyParts = rsaPart.split(" ssh-rsa ");
            if (keyParts.length > 1) key = keyParts[1];
        }

        Map<String, Object> data = new HashMap<>();
        data.put("ports", ports);
        data.put("key", key);
        return data;
    }


    /**
     * Writes (or replaces) the authorized_keys file for a user, adding the
     * given port to the permit list and writing the provided public key.
     *
     * Mirrors: (add-auth-key user pubkey port)
     */
    @SuppressWarnings("unchecked")
    public static ShellResult addAuthKey(String user, String pubkey, String port) {
        Map<String, Object> existing = authorizedKeysData(user);

        Set<String> ports = new HashSet<>();
        if (existing != null && existing.get("ports") != null)
            ports.addAll((List<String>) existing.get("ports"));
        ports.add(port);

        String permit = ports.stream()
                .map(p -> "permitopen=\"localhost:" + p + "\"")
                .collect(Collectors.joining(","));

        String content = "no-pty,no-X11-forwarding," + permit +
                ",command=\"/bin/echo do-not-send-commands\"" +
                ", ssh-rsa " + pubkey + "\n";

        return writeAuthorizedKeys(user, content);
    }

    /**
     * Rewrites authorized_keys removing the given port from the permit list.
     * If no ports remain, writes an empty file.
     *
     * Mirrors: (remove-auth-key user port)
     */
    @SuppressWarnings("unchecked")
    public static ShellResult removeAuthKey(String user, String port) {
        Map<String, Object> existing = authorizedKeysData(user);
        if (existing == null) return sh("true");   // nothing to remove

        List<String> remainingPorts = ((List<String>) existing.get("ports"))
                .stream()
                .filter(p -> !p.equals(port))
                .collect(Collectors.toList());

        String content = null;
        if (!remainingPorts.isEmpty()) {
            String permit = remainingPorts.stream()
                    .map(p -> "permitopen=\"localhost:" + p + "\"")
                    .collect(Collectors.joining(","));

            content = "no-pty,no-X11-forwarding," + permit +
                    ",command=\"/bin/echo do-not-send-commands\"" +
                    ", ssh-rsa " + existing.get("key") + "\n";
        } else {
            content = "";
        }

        return writeAuthorizedKeys(user, content);
    }

    /** Writes content to the user's authorized_keys via sudo tee. */
    private static ShellResult writeAuthorizedKeys(String user, String content) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "bash", "-c", "sudo tee /home/" + user + "/.ssh/authorized_keys");
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            proc.getOutputStream().write(content.getBytes());
            proc.getOutputStream().close();
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            int exit = proc.waitFor();
            return new ShellResult(exit, out, "");
        } catch (Exception e) {
            log.error("writeAuthorizedKeys failed: " + e.getMessage());
            return new ShellResult(-1, "", e.getMessage());
        }
    }

    // =======================================================================
    // System user management
    // =======================================================================

    /**
     * Creates a restricted system user with a home directory and .ssh folder.
     * Mirrors: (system-add-user user)
     */
    public static void systemAddUser(String user) {
        sh("sudo useradd --shell /bin/false -c '' '" + user + "'");
        sh("sudo usermod -p '*' " + user);
        sh("sudo mkdir /home/" + user);
        sh("sudo mkdir /home/" + user + "/.ssh");
        sh("sudo touch /home/" + user + "/.ssh/authorized_keys");
    }

    /**
     * Force-deletes a system user and their home directory.
     * Mirrors: (system-delete-user username)
     */
    public static void systemDeleteUser(String username) {
        sh("sudo userdel -f " + username);
        sh("sudo rm -rf /home/" + username);
    }

    /**
     * Returns true when the system user exists.
     * Mirrors: (system-user-exists user)
     */
    public static boolean systemUserExists(String user) {
        return sh("getent passwd " + user + " > /dev/null").exit() == 0;
    }

    /**
     * Creates the user if needed, then updates authorized_keys.
     * Mirrors: (add-user user pubkey port)
     */
    public static void addUser(String user, String pubkey, String port) {
        if (!systemUserExists(user)) systemAddUser(user);
        addAuthKey(user, pubkey, port);
    }

    // =======================================================================
    // System functions
    // =======================================================================

    /**
     * Sends SIGKILL to the given PID.
     * Mirrors: (kill-process pid)
     */
    public static ShellResult killProcess(String pid) {
        log.info("killing process with pid: " + pid);
        return sh("sudo kill -9 " + pid);
    }


    // =======================================================================
    // Monitor connection status
    // =======================================================================

    /**
     * Parses `sudo lsof -P -i -n` and returns all listening, non-root,
     * IPv4 sshd connections.
     *
     * Mirrors: (list-ssh-connections)
     */
    public static List<Map<String, Object>> listSshConnections() {
        String out = sh("sudo lsof -P -i -n").out();

        return Arrays.stream(out.split("\n"))
                .map(line -> line.trim().split("\\s+"))
                .filter(cols -> cols.length >= 10)
                .map(cols -> {
                    // cols: PROC PID USER FD PROTO DEV SIZE NODE FULL_PORT STATUS
                    String proc     = cols[0];
                    String pid      = cols[1];
                    String user     = cols[2];
                    String proto    = cols[4];
                    String fullPort = cols[8];
                    String status   = cols[9];

                    String[] hostPort = fullPort.split(":");
                    boolean external  = hostPort[0].equals("*");
                    String port       = hostPort.length > 1 ? hostPort[hostPort.length - 1] : "";
                    boolean open      = status.equals("(LISTEN)");

                    Map<String, Object> entry = new HashMap<>();
                    entry.put("proc",     proc);
                    entry.put("pid",      pid);
                    entry.put("user",     user);
                    entry.put("proto",    proto);
                    entry.put("port",     port);
                    entry.put("external", external);
                    entry.put("open?",    open);
                    return entry;
                })
                .filter(e -> {
                    String proc  = (String) e.get("proc");
                    String user  = (String) e.get("user");
                    String proto = (String) e.get("proto");
                    boolean open = (boolean) e.get("open?");
                    return "sshd".equals(proc) && !"root".equals(user)
                            && "IPv4".equals(proto) && open;
                })
                .collect(Collectors.toList());
    }

    /**
     * Returns only externally-listening SSH connections.
     * Mirrors: (list-sh-connected)
     */
    public static List<Map<String, Object>> listShConnected() {
        return listSshConnections().stream()
                .filter(e -> Boolean.TRUE.equals(e.get("external")))
                .collect(Collectors.toList());
    }

    /** Returns the current resource state atom. Mirrors: (list-resources) */
//    public static Map<String, Object> listResources() {
//        return resources.get();
//    }


    // =======================================================================
    // Port management
    // =======================================================================

    /**
     * Reserves a port, creates the system user, updates authorized_keys,
     * and opens the Lightsail firewall rule if type is "shell".
     *
     * Mirrors: (assign-port port type user key)
     */
//    @SuppressWarnings("unchecked")
//    public static Map<String, Object> assignPort(String port, String type, String user, String key) {
//        Map<String, Object> state = resources.get();
//        Map<String, Object> pool  = (Map<String, Object>) state.get("port-pool");
//        Set<Integer> typePorts    = (Set<Integer>) pool.get(type);
//
//        if (typePorts == null || !typePorts.contains(Integer.parseInt(port))) {
//            return Map.of("success", false, "port", port, "error", "port is busy");
//        }
//
//        // Atomically update state
//        resources.updateAndGet(db -> {
//            Map<String, Object> updated = new HashMap<>(db);
//
//            // Remove port from pool
//            Map<String, Object> updatedPool = new HashMap<>((Map<String, Object>) db.get("port-pool"));
//            Set<Integer> updatedPorts = new HashSet<>((Set<Integer>) updatedPool.get(type));
//            updatedPorts.remove(Integer.parseInt(port));
//            updatedPool.put(type, updatedPorts);
//            updated.put("port-pool", updatedPool);
//
//            // Add connection record
//            List<Map<String, Object>> conns = new ArrayList<>(
//                    (List<Map<String, Object>>) db.get("connections"));
//            Map<String, Object> conn = new HashMap<>();
//            conn.put("port",      port);
//            conn.put("user",      user);
//            conn.put("key",       key);
//            conn.put("type",      type);
//            conn.put("open?",     false);
//            conn.put("last-open", System.currentTimeMillis() / 1000);
//            conns.add(conn);
//            updated.put("connections", conns);
//
//            return updated;
//        });
//
//        addUser(user, key, port);
//
//        if ("shell".equals(type)) addInboundRule(port, "0.0.0.0/0");
//
//        return Map.of("success", true, "port", port, "user", user, "type", type);
//    }
//
//    /**
//     * Returns all connection records for a given port.
//     * Mirrors: (port-data port)
//     */
//    @SuppressWarnings("unchecked")
//    public static List<Map<String, Object>> portData(String port) {
//        List<Map<String, Object>> conns =
//                (List<Map<String, Object>>) resources.get().get("connections");
//        return conns.stream()
//                .filter(c -> port.equals(c.get("port")))
//                .collect(Collectors.toList());
//    }
//
//    /**
//     * Releases a port: kills SSH tunnel, removes user permissions, closes
//     * firewall rule, and returns the port to the pool.
//     *
//     * Mirrors: (release-port port)
//     */
//    @SuppressWarnings("unchecked")
//    public static Map<String, Object> releasePort(String port) {
//        List<Map<String, Object>> data = portData(port);
//        String user = data.isEmpty() ? null : (String) data.get(0).get("user");
//        String type = data.isEmpty() ? null : (String) data.get(0).get("type");
//
//        if (user != null) {
//            // Kill any active tunnel
//            List<Map<String, Object>> conns = listSshConnections();
//            conns.stream()
//                    .filter(c -> port.equals(c.get("port")))
//                    .findFirst()
//                    .ifPresent(tunnel -> killProcess((String) tunnel.get("pid")));
//
//            // Remove port from authorized_keys
//            removeAuthKey(user, port);
//
//            // Delete system user if no longer needed
//            if (authorizedKeysData(user) == null) systemDeleteUser(user);
//
//            // Close firewall rule for shell tunnels
//            if ("shell".equals(type)) removeInboundRule(port, "0.0.0.0/0");
//
//            // Return port to pool and remove connection record
//            resources.updateAndGet(db -> {
//                Map<String, Object> updated = new HashMap<>(db);
//
//                Map<String, Object> updatedPool = new HashMap<>((Map<String, Object>) db.get("port-pool"));
//                Set<Integer> poolPorts = new HashSet<>((Set<Integer>) updatedPool.getOrDefault(type, new HashSet<>()));
//                poolPorts.add(Integer.parseInt(port));
//                updatedPool.put(type, poolPorts);
//                updated.put("port-pool", updatedPool);
//
//                List<Map<String, Object>> remaining = ((List<Map<String, Object>>) db.get("connections"))
//                        .stream()
//                        .filter(c -> !port.equals(c.get("port")))
//                        .collect(Collectors.toList());
//                updated.put("connections", remaining);
//
//                return updated;
//            });
//
//            return Map.of("success", true, "port", port);
//
//        } else {
//            // No connection found — check if port is already in pool
//            Map<String, Object> pool = (Map<String, Object>) resources.get().get("port-pool");
//            Set<Integer> anyPool = pool.values().stream()
//                    .flatMap(s -> ((Set<Integer>) s).stream())
//                    .collect(Collectors.toSet());
//
//            if (anyPool.contains(Integer.parseInt(port))) {
//                return Map.of("success", true, "port", port);
//            } else {
//                return Map.of("success", false, "port", port, "message", "port out of range");
//            }
//        }
//    }
//
//    /**
//     * Returns success + port data for the given port.
//     * Mirrors: (port-status port)
//     */
//    public static Map<String, Object> portStatus(String port) {
//        return Map.of("success", true, "data", portData(port));
//    }
//
//    /**
//     * Returns the HTTP port and open flag for a user.
//     * Mirrors: (user-http-port user)
//     */
//    @SuppressWarnings("unchecked")
//    public static Map<String, Object> userHttpPort(String user) {
//        List<Map<String, Object>> conns =
//                (List<Map<String, Object>>) resources.get().get("connections");
//        String port = conns.stream()
//                .filter(c -> user.equals(c.get("user")) && "http".equals(c.get("type")))
//                .map(c -> (String) c.get("port"))
//                .findFirst().orElse(null);
//        return Map.of("port", port != null ? port : "", "open", true);
//    }

    /**
     * Returns SSH tunnel info for a given user from the live lsof output.
     * Mirrors: (get-tunnel-data user)
     */
    public static List<Map<String, Object>> getTunnelData(String user) {
        return listSshConnections().stream()
                .filter(c -> user.equals(c.get("user")))
                .collect(Collectors.toList());
    }

    // =======================================================================
    // System user listing
    // =======================================================================

    /**
     * Returns all system users whose name starts with "iot-".
     * Mirrors: (list-system-users)
     */
    public static List<String> listSystemUsers() {
        String out = sh("awk -F':' '{ print $1}' /etc/passwd").out();
        return Arrays.stream(out.split("\n"))
                .map(String::trim)
                .filter(u -> u.startsWith("iot-"))
                .collect(Collectors.toList());
    }

    // =======================================================================
    // SSH tunnel kill helpers
    // =======================================================================

    /**
     * Kills the SSH tunnel process for a user.
     * Mirrors: (kill-ssh-tunnel user)
     */
    public static ShellResult killSshTunnel(String user) {
        List<Map<String, Object>> tunnelData = getTunnelData(user);
        if (tunnelData.isEmpty()) {
            log.warn("No tunnel found for user: " + user);
            return new ShellResult(-1, "", "no tunnel found");
        }
        String pid = (String) tunnelData.get(0).get("pid");
        ShellResult result = sh("sudo kill -9 " + pid);
        log.info("killed tunnel for: " + user + " with pid: " + pid);
        return result;
    }

    /**
     * HTTP handler version: reads user from request params, kills tunnel.
     * Mirrors: (handle-kill-ssh-tunnel req)
     *
     * Returns a simple response map {status, headers, body}.
     */
    public static Map<String, Object> handleKillSshTunnel(Map<String, Object> req) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) req.get("params");
        String user = (String) params.get("id");

        ShellResult result = killSshTunnel(user);

        return Map.of(
                "status",  200,
                "headers", Map.of("Content-type", "application/json"),
                "body",    "{\"success\":" + (result.exit() == 0) + "}"
        );
    }

    // =======================================================================
    // Stale connection detection
    // =======================================================================

    /**
     * Returns true when a connection is not marked open and was last seen
     * more than 120 seconds ago.
     *
     * Mirrors: (is-stale conn)
     */
    public static boolean isStale(Map<String, Object> conn) {
        Object lastOpenObj = conn.get("last-open");
        long lastOpen = lastOpenObj != null ? ((Number) lastOpenObj).longValue() : 0L;
        boolean open = Boolean.TRUE.equals(conn.get("open?"));
        if (open) return false;
        long nowSec = System.currentTimeMillis() / 1000;
        return (nowSec - lastOpen) > 120;
    }


}
