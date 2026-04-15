package uy.plomo.cloud.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uy.plomo.cloud.dto.GatewayRegistrationRequest;
import uy.plomo.cloud.dto.TunnelRequest;
import uy.plomo.cloud.entity.Gateway;
import uy.plomo.cloud.entity.Tunnel;
import uy.plomo.cloud.entity.TunnelState;
import uy.plomo.cloud.entity.User;
import uy.plomo.cloud.repository.GatewayRepository;
import uy.plomo.cloud.repository.TunnelRepository;
import uy.plomo.cloud.repository.UserRepository;

import java.util.*;

@Service
@Transactional(readOnly = true)
@Slf4j
public class GatewayService {

    private final UserRepository userRepo;
    private final GatewayRepository gatewayRepo;
    private final TunnelRepository tunnelRepo;
    private final PasswordEncoder passwordEncoder;

    public GatewayService(UserRepository userRepo,
                          GatewayRepository gatewayRepo,
                          TunnelRepository tunnelRepo,
                          PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.gatewayRepo = gatewayRepo;
        this.tunnelRepo = tunnelRepo;
        this.passwordEncoder = passwordEncoder;
    }



    // -------------------------------------------------------------------------
    // Users
    // -------------------------------------------------------------------------

    /**
     * Cambia la contraseña del usuario autenticado.
     * Lanza ResponseStatusException(401) si la contraseña actual no coincide.
     */
    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        User user = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        log.info("Password changed for user {}", username);
    }

    /**
     * Registra un nuevo usuario hasheando la contraseña con BCrypt.
     * Lanza ConflictException si el username ya existe.
     */
    @Transactional
    public void registerUser(String username, String rawPassword) {
        if (userRepo.findByUsername(username).isPresent()) {
            throw new ConflictException("Username already exists: " + username);
        }
        String hash = passwordEncoder.encode(rawPassword);
        userRepo.save(User.create(username, hash));
        log.info("Registered user {}", username);
    }

    /**
     * Retorna el usuario con sus gateways ya cargados (JOIN FETCH — sin N+1).
     * Usado por LoginController para extraer los gateway IDs del token.
     */
    public User getUserWithGateways(String username) {
        return userRepo.findByUsernameWithGateways(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }

    public Map<String, Object> getUserSummary(String username) {
        User user = getUserWithGateways(username);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("username", user.getUsername());
        map.put("gateways", user.getGateways().stream().map(Gateway::getId).toList());
        return map;
    }

    public Map<String, Object> getGatewaySummary(String gwId) {
        Gateway gw = gatewayRepo.findById(gwId)
                .orElseThrow(() -> new ResourceNotFoundException("Gateway not found: " + gwId));

        List<Tunnel> tunnels = tunnelRepo.findAllByGatewayId(gwId);
        long activeCount = tunnels.stream()
                .filter(t -> t.getState() == TunnelState.ACTIVE)
                .count();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("gateway_id", gw.getId());
        map.put("status", gw.getStatus().name());
        map.put("tunnel_count", tunnels.size());
        map.put("active_tunnel_count", activeCount);
        return map;
    }

    /**
     * Lista de tunnels de un gateway como Map<tunnelId, tunnelFields>.
     * Usa existsById + findAllByGatewayId para que el unit test pueda mockear
     * ambas llamadas por separado sin necesidad de JOIN FETCH.
     */
    public Map<String, Object> getTunnelList(String gwId) {
        if (!gatewayRepo.existsById(gwId)) {
            throw new ResourceNotFoundException("Gateway not found: " + gwId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Tunnel t : tunnelRepo.findAllByGatewayId(gwId)) {
            result.put(t.getId(), tunnelToMap(t));
        }
        return result;
    }

    /**
     * Detalle de un tunnel específico como Map.
     * Valida que el tunnel pertenezca al gateway indicado.
     */
    public Map<String, Object> getTunnelDetail(String gwId, String tunnelId) {
        return tunnelRepo.findByIdAndGatewayId(tunnelId, gwId)
                .map(this::tunnelToMap)
                .orElseThrow(() -> new ResourceNotFoundException("Tunnel not found: " + tunnelId));
    }

    /**
     * Registra un nuevo gateway vinculado al usuario autenticado.
     * Lanza ConflictException si el gateway_id ya existe.
     */
    @Transactional
    public String registerGateway(String username, GatewayRegistrationRequest req) {
        if (gatewayRepo.existsById(req.gatewayId())) {
            throw new ConflictException("Gateway already exists: " + req.gatewayId());
        }
        User user = userRepo.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
        Gateway gw = Gateway.create(req.gatewayId(), req.publicKey(), user);
        gatewayRepo.save(gw);
        log.info("Registered gateway {} for user {}", req.gatewayId(), username);
        return req.gatewayId();
    }

    /**
     * Crea un tunnel y retorna el ID generado.
     */
    @Transactional
    public String createTunnel(String gwId, TunnelRequest req) {
        Gateway gw = gatewayRepo.findById(gwId)
                .orElseThrow(() -> new ResourceNotFoundException("Gateway not found: " + gwId));
        Tunnel tunnel = Tunnel.create(req.name(), req.srcAddr(), req.srcPort(),
                                     req.dstPort(), req.usesThisServer(), gw);
        tunnelRepo.save(tunnel);
        log.info("Created tunnel {} on gateway {}", tunnel.getId(), gwId);
        return tunnel.getId();
    }

    /**
     * Actualiza los campos editables de un tunnel existente.
     * Hibernate dirty-checking persiste los cambios al hacer commit — no necesita save().
     */
    @Transactional
    public void updateTunnel(String gwId, String tunnelId, TunnelRequest req) {
        Tunnel tunnel = tunnelRepo.findByIdAndGatewayId(tunnelId, gwId)
                .orElseThrow(() -> new ResourceNotFoundException("Tunnel not found: " + tunnelId));
        tunnel.update(req);
        log.info("Updated tunnel {} on gateway {}", tunnelId, gwId);
    }

    /**
     * Elimina un tunnel. El caller debe haberse asegurado de detenerlo antes.
     */
    @Transactional
    public void deleteTunnel(String gwId, String tunnelId) {
        Tunnel tunnel = tunnelRepo.findByIdAndGatewayId(tunnelId, gwId)
                .orElseThrow(() -> new ResourceNotFoundException("Tunnel not found: " + tunnelId));
        tunnelRepo.delete(tunnel);
        log.info("Deleted tunnel {} from gateway {}", tunnelId, gwId);
    }

    /**
     * Marca un tunnel como ACTIVE y registra el puerto asignado en el servidor.
     * Llamado por el flujo de start después de que el gateway confirma la conexión.
     */
    @Transactional
    public void markTunnelActive(String gwId, String tunnelId, int assignedPort) {
        Tunnel tunnel = tunnelRepo.findByIdAndGatewayId(tunnelId, gwId)
                .orElseThrow(() -> new ResourceNotFoundException("Tunnel not found: " + tunnelId));
        tunnel.setState(TunnelState.ACTIVE);
        tunnel.setAssignedPort(assignedPort);
        log.info("Tunnel {} on gateway {} marked ACTIVE on port {}", tunnelId, gwId, assignedPort);
    }

    /**
     * Marca un tunnel como STOPPED y libera el puerto asignado.
     * Llamado por el flujo de stop después de que el gateway confirma el cierre.
     */
    @Transactional
    public void markTunnelStopped(String gwId, String tunnelId) {
        Tunnel tunnel = tunnelRepo.findByIdAndGatewayId(tunnelId, gwId)
                .orElseThrow(() -> new ResourceNotFoundException("Tunnel not found: " + tunnelId));
        tunnel.setState(TunnelState.STOPPED);
        tunnel.setAssignedPort(null);
        log.info("Tunnel {} on gateway {} marked STOPPED", tunnelId, gwId);
    }

    private Map<String, Object> tunnelToMap(Tunnel t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", t.getName());
        m.put("src_addr", t.getSrcAddr());
        m.put("src_port", t.getSrcPort());
        m.put("dst_port", t.getDstPort());
        m.put("use_this_server", t.isUseThisServer() ? "on" : "off");
        m.put("state", t.getState().name());
        return m;
    }

    public static class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException(String message) { super(message); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }
}
