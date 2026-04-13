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
import uy.plomo.cloud.repository.UserRepository;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts requests to /api/v1/{gwId}/** and verifies the authenticated
 * user owns the requested gateway. Returns 404 on mismatch to avoid
 * revealing whether the gateway exists.
 *
 * Ownership is verified against the database — not the JWT — so that
 * gateways added after login are immediately accessible.
 */
@Component
@Slf4j
public class GatewayOwnershipFilter extends OncePerRequestFilter {

    private static final Pattern GW_PATH_PATTERN =
            Pattern.compile("^/api/v1/([^/]+)/.*$");

    private static final Set<String> NON_GATEWAY_SEGMENTS = Set.of("summary", "gateways", "user");

    private final UserRepository userRepository;

    public GatewayOwnershipFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
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

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String username = auth.getName();

        boolean owns = userRepository.findByUsernameWithGateways(username)
                .map(user -> user.getGateways().stream()
                        .anyMatch(gw -> gw.getId().equals(gwId)))
                .orElse(false);

        if (!owns) {
            log.warn("User '{}' attempted to access unowned gateway '{}'", username, gwId);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        filterChain.doFilter(request, response);
    }
}