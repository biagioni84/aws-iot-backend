package uy.plomo.cloud.services;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uy.plomo.cloud.platform.PortPool;


import static uy.plomo.cloud.platform.LightsailRemoteAccess.*;

@Service
public class PortPoolService {

    private final PortPool portPool;
    @Getter
    @Value("${tunnel.server.host}")
    private String serverHost;

    public PortPoolService(PortPool portPool) {
        this.portPool = portPool;
    }

    public void releasePort(String port){
        portPool.releasePort(Integer.parseInt(port));
    }
    public String assignPort(String dstPort, String sshUser, String pubkey) {
        int port = portPool.acquirePort(sshUser, Integer.parseInt(dstPort));
        addUser(sshUser,pubkey,String.valueOf(port));
        addInboundRule(String.valueOf(port),"0.0.0.0/0");
        //(add-inbound-rule port "0.0.0.0/0")
        // TODO: sudo nano /etc/ssh/sshd_config
        //Match User [YOUR_USERNAME]
        //    PermitListen 8080
        //    AllowTcpForwarding remote
        //    X11Forwarding no
        //    AllowAgentForwarding no
        //    ForceCommand /bin/false
        return String.valueOf(port);
    }

}