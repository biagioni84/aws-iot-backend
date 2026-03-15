package uy.plomo.cloud.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import uy.plomo.cloud.security.GatewayOwnershipFilter;
import uy.plomo.cloud.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;

import java.util.List;

@EnableMethodSecurity
@EnableWebSecurity
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final GatewayOwnershipFilter gatewayOwnershipFilter;

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    // FIX: constructor injection en lugar de @Autowired field injection
    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter,
                          GatewayOwnershipFilter gatewayOwnershipFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.gatewayOwnershipFilter = gatewayOwnershipFilter;
    }

    private static final String[] SWAGGER_WHITELIST = {
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/swagger-resources/**",
            "/webjars/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(request -> {
                    CorsConfiguration config = new CorsConfiguration();
                    config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
                    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                    config.setAllowedHeaders(List.of("*"));
                    config.setAllowCredentials(true);
                    return config;
                }))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers(SWAGGER_WHITELIST).permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(gatewayOwnershipFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

}