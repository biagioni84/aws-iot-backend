package uy.plomo.cloud.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "gateways")
public class Gateway {

    @Id
    @Column(length = 100)
    private String id;

    @Column(name = "public_key", columnDefinition = "TEXT", nullable = false)
    private String publicKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GatewayStatus status = GatewayStatus.UNKNOWN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @OneToMany(mappedBy = "gateway", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Tunnel> tunnels = new ArrayList<>();

    protected Gateway() {}

    public static Gateway create(String id, String publicKey, User owner) {
        Gateway g = new Gateway();
        g.id = id;
        g.publicKey = publicKey;
        g.owner = owner;
        return g;
    }

    public String getId()             { return id; }
    public String getPublicKey()      { return publicKey; }
    public GatewayStatus getStatus()  { return status; }
    public User getOwner()            { return owner; }
    public List<Tunnel> getTunnels()  { return tunnels; }

    public void setStatus(GatewayStatus status) { this.status = status; }
}
