package uy.plomo.cloud.services;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uy.plomo.cloud.platform.LightsailRemoteAccess;
import uy.plomo.cloud.platform.PortPool;

@Service
@Slf4j
public class PortPoolService {

    private final PortPool portPool;
    private final LightsailRemoteAccess lightsailRemoteAccess;

    @Getter
    @Value("${tunnel.server.host}")
    private String serverHost;

    public PortPoolService(PortPool portPool, LightsailRemoteAccess lightsailRemoteAccess) {
        this.portPool = portPool;
        this.lightsailRemoteAccess = lightsailRemoteAccess;
    }

    /**
     * Reserva el puerto solicitado, registra la key SSH del gateway en
     * authorized_keys y abre el puerto en el firewall de Lightsail.
     */
    public String assignPort(String dstPort, String sshUser, String pubkey) {
        int port = portPool.acquirePort(sshUser, Integer.parseInt(dstPort));
        lightsailRemoteAccess.addGatewayKey(pubkey, String.valueOf(port));
        lightsailRemoteAccess.addInboundRule(String.valueOf(port));
        return String.valueOf(port);
    }

    /**
     * Libera el puerto en el pool, elimina la entrada de authorized_keys
     * y cierra el puerto en el firewall de Lightsail.
     */
    public void releasePort(String port) {
        portPool.releasePort(Integer.parseInt(port));
        lightsailRemoteAccess.removeGatewayKeyByPort(port);
        lightsailRemoteAccess.removeInboundRule(port);
        log.info("Released port {}", port);
    }
}