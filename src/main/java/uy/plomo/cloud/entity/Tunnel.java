package uy.plomo.cloud.entity;

import jakarta.persistence.*;
import uy.plomo.cloud.dto.TunnelRequest;

import java.util.UUID;

@Entity
@Table(name = "tunnels")
public class Tunnel {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(name = "src_addr", nullable = false)
    private String srcAddr;

    @Column(name = "src_port", nullable = false, length = 10)
    private String srcPort;

    @Column(name = "dst_port", nullable = false, length = 10)
    private String dstPort;

    @Column(name = "use_this_server", nullable = false)
    private boolean useThisServer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TunnelState state = TunnelState.STOPPED;

    @Column(name = "assigned_port")
    private Integer assignedPort;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gateway_id", nullable = false)
    private Gateway gateway;

    protected Tunnel() {}

    public static Tunnel create(String name, String srcAddr, String srcPort,
                                String dstPort, boolean useThisServer, Gateway gateway) {
        Tunnel t = new Tunnel();
        t.id = UUID.randomUUID().toString();
        t.name = name;
        t.srcAddr = srcAddr;
        t.srcPort = srcPort;
        t.dstPort = dstPort;
        t.useThisServer = useThisServer;
        t.gateway = gateway;
        return t;
    }

    public void update(TunnelRequest req) {
        this.name = req.name();
        this.srcAddr = req.srcAddr();
        this.srcPort = req.srcPort();
        this.dstPort = req.dstPort();
        this.useThisServer = req.usesThisServer();
    }

    public String getId()             { return id; }
    public String getName()           { return name; }
    public String getSrcAddr()        { return srcAddr; }
    public String getSrcPort()        { return srcPort; }
    public String getDstPort()        { return dstPort; }
    public boolean isUseThisServer()  { return useThisServer; }
    public TunnelState getState()     { return state; }
    public Integer getAssignedPort()  { return assignedPort; }
    public Gateway getGateway()       { return gateway; }

    public void setState(TunnelState state)           { this.state = state; }
    public void setAssignedPort(Integer assignedPort) { this.assignedPort = assignedPort; }
}
