package uy.plomo.cloud.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lightsail.LightsailClient;
import software.amazon.awssdk.services.lightsail.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
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
 *   StrictModes no
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

    private final LightsailClient lightsail;

    public LightsailRemoteAccess(@Value("${aws.region}") String region) {
        this.lightsail = LightsailClient.builder()
                .region(Region.of(region))
                .build();
    }

    private String authKeysPath() {
        return "/home/" + tunnelUser + "/.ssh/authorized_keys";
    }

    /**
     * Agrega o actualiza la línea de authorized_keys para el gateway dado.
     * Si ya existe una línea con esa pubkey, actualiza su puerto.
     * Si ya existe una línea con ese puerto (otro gateway), lanza excepción.
     */
    public void addGatewayKey(String pubkey, String port) {
        List<String> lines = readAuthorizedKeys();

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
        Path path = Path.of(authKeysPath());
        try {
            if (!Files.exists(path)) return new ArrayList<>();
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (IOException e) {
            log.warn("Could not read {}: {}", path, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeAuthorizedKeys(List<String> lines) {
        String content = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
        writeFile(authKeysPath(), content);
    }

    /**
     * Opens a Lightsail firewall port via AWS SDK.
     */
    public ShellResult addInboundRule(String port) {
        log.info("Opening Lightsail port {}", port);
        try {
            int p = Integer.parseInt(port);
            lightsail.openInstancePublicPorts(OpenInstancePublicPortsRequest.builder()
                    .instanceName(instanceName)
                    .portInfo(PortInfo.builder()
                            .fromPort(p)
                            .toPort(p)
                            .protocol(NetworkProtocol.TCP)
                            .cidrs(EXTERNAL_CIDR)
                            .build())
                    .build());
            return new ShellResult(0, "Port " + port + " opened", "");
        } catch (Exception e) {
            log.error("Failed to open port {}: {}", port, e.getMessage());
            return new ShellResult(1, "", e.getMessage());
        }
    }

    /**
     * Closes a Lightsail firewall port via AWS SDK.
     */
    public ShellResult removeInboundRule(String port) {
        log.info("Closing Lightsail port {}", port);
        try {
            int p = Integer.parseInt(port);
            lightsail.closeInstancePublicPorts(CloseInstancePublicPortsRequest.builder()
                    .instanceName(instanceName)
                    .portInfo(PortInfo.builder()
                            .fromPort(p)
                            .toPort(p)
                            .protocol(NetworkProtocol.TCP)
                            .cidrs(EXTERNAL_CIDR)
                            .build())
                    .build());
            return new ShellResult(0, "Port " + port + " closed", "");
        } catch (Exception e) {
            log.error("Failed to close port {}: {}", port, e.getMessage());
            return new ShellResult(1, "", e.getMessage());
        }
    }

    /**
     * Parsea `lsof -P -i -n` y devuelve todas las conexiones sshd activas.
     * Requiere que el contenedor corra con --pid=host.
     */
    public List<Map<String, Object>> listSshConnections() {
        String out = exec("lsof", "-P", "-i", "-n").out();

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

    public List<Map<String, Object>> listShConnected() {
        return listSshConnections().stream()
                .filter(e -> Boolean.TRUE.equals(e.get("external")))
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getTunnelData(String user) {
        return listSshConnections().stream()
                .filter(c -> user.equals(c.get("user")))
                .collect(Collectors.toList());
    }

    public ShellResult killSshTunnelByPort(String port) {
        List<Map<String, Object>> connections = listSshConnections();
        Optional<Map<String, Object>> match = connections.stream()
                .filter(c -> port.equals(c.get("port")))
                .findFirst();

        if (match.isEmpty()) {
            log.warn("No SSH tunnel found listening on port {}", port);
            return new ShellResult(-1, "", "no tunnel found on port " + port);
        }

        String pid  = (String) match.get().get("pid");
        String user = (String) match.get().get("user");
        ShellResult result = exec("kill", "-9", pid);
        log.info("Killed SSH tunnel on port {} (user={}, pid={})", port, user, pid);
        return result;
    }

    public ShellResult killSshTunnel(String user) {
        List<Map<String, Object>> tunnelData = getTunnelData(user);
        if (tunnelData.isEmpty()) {
            log.warn("No tunnel found for user: {}", user);
            return new ShellResult(-1, "", "no tunnel found");
        }
        String pid = (String) tunnelData.get(0).get("pid");
        ShellResult result = exec("kill", "-9", pid);
        log.info("Killed tunnel for user {} with pid {}", user, pid);
        return result;
    }

    public ShellResult killProcess(String pid) {
        log.info("Killing process with pid: {}", pid);
        return exec("kill", "-9", pid);
    }

    public boolean isStale(Map<String, Object> conn) {
        Object lastOpenObj = conn.get("last-open");
        long lastOpen = lastOpenObj != null ? ((Number) lastOpenObj).longValue() : 0L;
        boolean open = Boolean.TRUE.equals(conn.get("open?"));
        if (open) return false;
        return (System.currentTimeMillis() / 1000 - lastOpen) > 120;
    }

    /**
     * Escribe contenido a un archivo usando Java NIO con escritura atómica
     * (archivo temporal + move) para evitar lecturas parciales por sshd.
     */
    private void writeFile(String pathStr, String content) {
        Path target = Path.of(pathStr);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".authorized_keys_", ".tmp");
            try {
                Files.writeString(tmp, content, StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING);
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-rw----"));
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            log.debug("Wrote {}", pathStr);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + pathStr, e);
        }
    }

    /**
     * Ejecuta un comando via ProcessBuilder — sin shell injection.
     * Para lsof/kill requiere que el contenedor corra con --pid=host y --cap-add=KILL.
     */
    private ShellResult exec(String... args) {
        String cmdDisplay = String.join(" ", args);
        log.debug("Executing: {}", cmdDisplay);

        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            proc = pb.start();
            proc.getOutputStream().close();

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
}
