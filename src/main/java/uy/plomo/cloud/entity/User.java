package uy.plomo.cloud.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @OneToMany(mappedBy = "owner", fetch = FetchType.LAZY)
    private List<Gateway> gateways = new ArrayList<>();

    protected User() {}

    public static User create(String username, String passwordHash) {
        User u = new User();
        u.username = username;
        u.passwordHash = passwordHash;
        return u;
    }

    public Long getId()           { return id; }
    public String getUsername()   { return username; }
    public String getPasswordHash() { return passwordHash; }
    public List<Gateway> getGateways() { return gateways; }
}
