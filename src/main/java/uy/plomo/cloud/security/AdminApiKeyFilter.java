package uy.plomo.cloud.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Protects POST /auth/register with a static admin API key.
 * The key is configured via the admin.api-key property.
 */
@Component
public class AdminApiKeyFilter extends OncePerRequestFilter {

    private static final String REGISTER_PATH = "/auth/register";
    private static final String HEADER = "X-Admin-Key";

    @Value("${admin.api-key}")
    private String adminApiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (!HttpMethod.POST.matches(request.getMethod())
                || !REGISTER_PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(HEADER);
        if (!adminApiKey.equals(provided)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid or missing admin API key");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
