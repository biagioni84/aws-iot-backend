package uy.plomo.cloud.security;


import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Collection;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {
    @Value("${jwt.secret}")
    private String jwtSecret;

    private Key key;
    @PostConstruct
    private void init() {
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Value("${jwt.expiration-ms:86400000}")
    private long expirationMs;

    public String generateToken(String username,
                                Collection<? extends GrantedAuthority> authorities) {
        List<String> roles = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        return Jwts.builder()
                .setSubject(username)
                .claim("roles", roles)
//                .claim("gateways", gatewayIds)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    public String validate(String token) {
        // returns username
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }


    public List<String> extractGateways(Claims claims) {
        Object gateways = claims.get("gateways");
        if (gateways instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }
}