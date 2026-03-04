package uy.plomo.cloud.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts requests to /api/v1/{gwId}/** and verifies the authenticated
 * user owns the requested gateway. Returns 404 on mismatch to avoid
 * revealing whether the gateway exists.
 */
@Component
@Slf4j
public class GatewayOwnershipFilter extends OncePerRequestFilter {

    private static final Pattern GW_PATH_PATTERN =
            Pattern.compile("^/api/v1/([^/]+)/.*$");

    private final JwtService jwtService;

    public GatewayOwnershipFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        Matcher matcher = GW_PATH_PATTERN.matcher(path);

        if (!matcher.matches()) {
            // Not a gateway-specific route — pass through
            filterChain.doFilter(request, response);
            return;
        }

        String gwId = matcher.group(1);

        // Skip ownership check for non-gateway path segments (e.g. "summary", "gateways")
        if (gwId.equals("summary") || gwId.equals("gateways")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // No token — let JwtAuthenticationFilter handle it
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtService.extractAllClaims(authHeader.substring(7));
            List<String> ownedGateways = jwtService.extractGateways(claims);

            if (!ownedGateways.contains(gwId)) {
                log.warn("User '{}' attempted to access unowned gateway '{}'",
                        claims.getSubject(), gwId);
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        } catch (Exception e) {
            // Invalid token — let JwtAuthenticationFilter return 401
            filterChain.doFilter(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }
}