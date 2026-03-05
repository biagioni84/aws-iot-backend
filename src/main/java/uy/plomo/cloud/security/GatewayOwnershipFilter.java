package uy.plomo.cloud.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import io.jsonwebtoken.Claims;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts requests to /api/v1/{gwId}/** and verifies the authenticated
 * user owns the requested gateway. Returns 404 on mismatch to avoid
 * revealing whether the gateway exists.
 *
 * Reads gateway ownership from the Claims stored in the Authentication details
 * by JwtAuthenticationFilter — no second JWT parse needed.
 */
@Component
@Slf4j
public class GatewayOwnershipFilter extends OncePerRequestFilter {

    private static final Pattern GW_PATH_PATTERN =
            Pattern.compile("^/api/v1/([^/]+)/.*$");

    // Non-gateway segments that appear at /api/v1/{segment}/ — add here if new routes are added
    private static final Set<String> NON_GATEWAY_SEGMENTS = Set.of("summary", "gateways");

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
            filterChain.doFilter(request, response);
            return;
        }

        String gwId = matcher.group(1);

        if (NON_GATEWAY_SEGMENTS.contains(gwId)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Use the Authentication already set by JwtAuthenticationFilter.
        // If it's null, the user is unauthenticated — let Spring Security handle it.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        // JwtAuthenticationFilter stores the full Claims in auth.getDetails()
        // to avoid parsing the JWT a second time here.
        List<String> ownedGateways = extractGatewaysFromAuth(auth);

        if (!ownedGateways.contains(gwId)) {
            log.warn("User '{}' attempted to access unowned gateway '{}'", auth.getName(), gwId);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        filterChain.doFilter(request, response);
    }

    @SuppressWarnings("unchecked")
    private List<String> extractGatewaysFromAuth(Authentication auth) {
        if (auth.getDetails() instanceof Claims claims) {
            return jwtService.extractGateways(claims);
        }
        // Fallback: should not happen in normal flow, but safe to handle
        return List.of();
    }
}