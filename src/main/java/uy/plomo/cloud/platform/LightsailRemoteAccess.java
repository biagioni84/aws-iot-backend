package uy.plomo.cloud.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Gestión del servidor SSH en Lightsail.
 *
 * Cada gateway tiene una línea en /home/{tunnelUser}/.ssh/authorized_keys:
 *   restrict,port-forwarding,permitlisten="0.0.0.0:PORT" KEY_TYPE PUBKEY
 *
 * OpenSSH enforcea que cada key solo puede abrir el puerto declarado en
 * su permitlisten, eliminando la necesidad de validación extra en Java.
 *
 * El sshd_config se configura una sola vez con Match User tunneluser:
 *   AllowTcpForwarding yes
 *   GatewayPorts clientspecified
 *   PermitTTY no
 *   ForceCommand echo 'Tunnel only'
 */
@Service
@Slf4j
public class LightsailRemoteAccess {

    public record ShellResult(int exit, String out, String err) {}

    private static final String EXTERNAL_CIDR = "0.0.0.0/0";
    private static final int EXEC_TIMEOUT_SECONDS = 30;

    @Value("${iot.instanceName}")
    private String instanceName;

    @Value("${ssh.tunnel.user:tunneluser}")
    private String tunnelUser;

    private String authKeysPath() {
        return "/home/" + tunnelUser + "/.ssh/authorized_keys";
    }

    // =========================================================================
    // authorized_keys management
    // =========================================================================

    /**
     * Agrega o actualiza la línea de authorized_keys para el gateway dado.
     * Si ya existe una línea con esa pubkey, actualiza su puerto.
     * Si ya existe una línea con ese puerto (otro gateway), lanza excepción.
     */
    public void addGatewayKey(String pubkey, String port) {
        List<String> lines = readAuthorizedKeys();

        // Verificar que el puerto no esté ya asignado a otra key
        String portMarker = "permitlisten=\"0.0.0.0:" + port + "\"";
        boolean portInUseByOther = lines.stream()
                .anyMatch(l -> l.contains(portMarker) && !l.contains(pubkey));
        if (portInUseByOther) {
            throw new IllegalStateException("Port " + port + " already assigned to another gateway");
        }

        String newLine = buildKeyLine(pubkey, port);
        boolean updated = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(pubkey)) {
                lines.set(i, newLine);
                updated = true;
                break;
            }
        }
        if (!updated) lines.add(newLine);

        writeAuthorizedKeys(lines);
        log.info("Added/updated authorized_keys entry for port {}", port);
    }

    /**
     * Elimina la línea de authorized_keys que corresponde al puerto dado.
     * Se busca por puerto en lugar de pubkey porque al liberar un puerto
     * no siempre se tiene la pubkey disponible.
     */
    public void removeGatewayKeyByPort(String port) {
        List<String> lines = readAuthorizedKeys();
        String portMarker = "permitlisten=\"0.0.0.0:" + port + "\"";
        int before = lines.size();
        lines.removeIf(l -> l.contains(portMarker));
        if (lines.size() < before) {
            writeAuthorizedKeys(lines);
            log.info("Removed authorized_keys entry for port {}", port);
        } else {
            log.warn("No authorized_keys entry found for port {}", port);
        }
    }

    private String buildKeyLine(String pubkey, String port) {
        return String.format("restrict,port-forwarding,permitlisten=\"0.0.0.0:%s\" %s", port, pubkey);
    }

    private List<String> readAuthorizedKeys() {
        ShellResult result = exec("sudo", "cat", authKeysPath());
        if (result.exit() != 0 || result.out().isBlank()) return new ArrayList<>();
        return Arrays.stream(result.out().split("\n"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void writeAuthorizedKeys(List<String> lines) {
        String content = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
        writeFile(authKeysPath(), content);
    }

    // =========================================================================
    // Firewall management (Lightsail)
    // =========================================================================

    public ShellResult addInboundRule(String port) {
        log.info("Opening Lightsail port {}", port);
        return exec("aws", "lightsail", "open-instance-public-ports",
                "--instance-name", instanceName,
                "--port-info", String.format("fromPort=%s,protocol=TCP,toPort=%s,cidrs=%s",
                        port, port, EXTERNAL_CIDR));
    }

    public ShellResult removeInboundRule(String port) {
        log.info("Closing Lightsail port {}", port);
        return exec("aws", "lightsail", "close-instance-public-ports",
                "--instance-name", instanceName,
                "--port-info", String.format("fromPort=%s,protocol=TCP,toPort=%s,cidrs=%s",
                        port, port, EXTERNAL_CIDR));
    }

    // =========================================================================
    // SSH connection monitoring
    // =========================================================================

    /**
     * Parsea `sudo lsof -P -i -n` y devuelve todas las conexiones sshd
     * activas (no root, IPv4, en estado LISTEN).
     */
    public List<Map<String, Object>> listSshConnections() {
        String out = exec("sudo", "lsof", "-P", "-i", "-n").out();

        return Arrays.stream(out.split("\n"))
                .map(line -> line.trim().split("\\s+"))
                .filter(cols -> cols.length >= 10)
                .map(cols -> {
                    String proc     = cols[0];
                    String pid      = cols[1];
                    String user     = cols[2];
                    String proto    = cols[4];
                    String fullPort = cols[8];
                    String status   = cols[9];

                    String[] hostPort = fullPort.split(":");
                    boolean external  = hostPort[0].equals("*");
                    String port       = hostPort.length > 1 ? hostPort[hostPort.length - 1] : "";
                    boolean open      = "(LISTEN)".equals(status);

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
                .filter(e -> "sshd".equals(e.get("proc"))
                        && !"root".equals(e.get("user"))
                        && "IPv4".equals(e.get("proto"))
                        && Boolean.TRUE.equals(e.get("open?")))
                .collect(Collectors.toList());
    }

    /** Devuelve solo las conexiones con escucha externa (host == *). */
    public List<Map<String, Object>> listShConnected() {
        return listSshConnections().stream()
                .filter(e -> Boolean.TRUE.equals(e.get("external")))
                .collect(Collectors.toList());
    }

    /** Devuelve los datos de túnel SSH para un usuario dado. */
    public List<Map<String, Object>> getTunnelData(String user) {
        return listSshConnections().stream()
                .filter(c -> user.equals(c.get("user")))
                .collect(Collectors.toList());
    }

    /** Mata el proceso SSH del usuario por PID. */
    public ShellResult killSshTunnel(String user) {
        List<Map<String, Object>> tunnelData = getTunnelData(user);
        if (tunnelData.isEmpty()) {
            log.warn("No tunnel found for user: {}", user);
            return new ShellResult(-1, "", "no tunnel found");
        }
        String pid = (String) tunnelData.get(0).get("pid");
        ShellResult result = exec("sudo", "kill", "-9", pid);
        log.info("Killed tunnel for user {} with pid {}", user, pid);
        return result;
    }

    /** Mata un proceso por PID. */
    public ShellResult killProcess(String pid) {
        log.info("Killing process with pid: {}", pid);
        return exec("sudo", "kill", "-9", pid);
    }

    /**
     * Devuelve true si la conexión lleva más de 120 segundos sin estar abierta.
     */
    public boolean isStale(Map<String, Object> conn) {
        Object lastOpenObj = conn.get("last-open");
        long lastOpen = lastOpenObj != null ? ((Number) lastOpenObj).longValue() : 0L;
        boolean open = Boolean.TRUE.equals(conn.get("open?"));
        if (open) return false;
        return (System.currentTimeMillis() / 1000 - lastOpen) > 120;
    }

    // =========================================================================
    // Core execution — sin shell injection
    // =========================================================================

    /**
     * Ejecuta un comando con argumentos separados via ProcessBuilder.
     * Nunca concatena strings en un bash -c, eliminando el riesgo de
     * shell injection independientemente del contenido de los argumentos.
     */
    private ShellResult exec(String... args) {
        String cmdDisplay = String.join(" ", args);
        log.debug("Executing: {}", cmdDisplay);

        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            proc = pb.start();
            proc.getOutputStream().close(); // evita bloqueos interactivos

            boolean finished = proc.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                log.error("Timeout executing: {}", cmdDisplay);
                proc.destroyForcibly();
                return new ShellResult(-1, "", "Timeout");
            }

            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            log.debug("Exit {}: {}", proc.exitValue(), out);
            return new ShellResult(proc.exitValue(), out, "");

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Error executing {}: {}", cmdDisplay, e.getMessage());
            return new ShellResult(-1, "", e.getMessage());
        } finally {
            if (proc != null && proc.isAlive()) proc.destroy();
        }
    }

    /**
     * Escribe contenido a un archivo via `sudo tee`.
     * Usa ProcessBuilder con args separados — sin shell injection.
     */
    private void writeFile(String path, String content) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sudo", "tee", path);
            pb.redirectErrorStream(true); // merge stderr into stdout so we can log it
            Process proc = pb.start();
            proc.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
            proc.getOutputStream().close();
            boolean finished = proc.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!finished) {
                proc.destroyForcibly();
                throw new RuntimeException("tee timed out writing " + path);
            }
            if (proc.exitValue() != 0) {
                log.error("tee failed writing {}: {}", path, out);
                throw new RuntimeException("tee exited with " + proc.exitValue() + ": " + out);
            }
            log.debug("Wrote {}", path);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to write " + path, e);
        }
    }
}