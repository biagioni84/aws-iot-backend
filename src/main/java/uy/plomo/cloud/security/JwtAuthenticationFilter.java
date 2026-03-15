package uy.plomo.cloud.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String jwt = authHeader.substring(7);
        try {
            Claims claims = jwtService.extractAllClaims(jwt);
            String username = claims.getSubject();

            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                List<String> roles = claims.get("roles", List.class);
                List<SimpleGrantedAuthority> authorities = roles == null
                        ? List.of()
                        : roles.stream().map(SimpleGrantedAuthority::new).toList();

                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(username, null, authorities);
                authToken.setDetails(claims);
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        } catch (JwtException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}