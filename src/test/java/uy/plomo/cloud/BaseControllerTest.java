package uy.plomo.cloud;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import uy.plomo.cloud.entity.Gateway;
import uy.plomo.cloud.entity.User;
import uy.plomo.cloud.repository.UserRepository;
import uy.plomo.cloud.security.JwtService;
import uy.plomo.cloud.services.GatewayService;
import uy.plomo.cloud.services.MqttService;
import uy.plomo.cloud.services.PortPoolService;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(PostgresTestConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-key-that-is-long-enough-for-hmac",
        "jwt.expiration-ms=86400000",
        "aws.region=us-east-1",
        "aws.iot.endpoint=test-endpoint",
        "aws.iot.clientId=test-client",
        "cors.allowed-origins=http://localhost:5173",
        "tunnel.server.host=test-server",
        "port.pool.start=9000",
        "port.pool.end=9010",
        "iot.instanceName=test-instance",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
public abstract class BaseControllerTest {

    @MockitoBean protected GatewayService gatewayService;
    @MockitoBean protected MqttService mqttService;
    @MockitoBean protected PortPoolService portPoolService;
    @MockitoBean protected UserRepository userRepository;

    @Autowired private WebApplicationContext context;
    @Autowired protected JwtService jwtService;

    protected MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    /**
     * Para controllers que devuelven CompletableFuture, Spring MVC procesa la
     * respuesta de forma asíncrona. MockMvc necesita dos pasos:
     *   1. perform()       → inicia el request
     *   2. asyncDispatch() → espera que el CompletableFuture se resuelva
     *
     * Para respuestas síncronas del filter chain (403, 404 del ownership filter),
     * usar mockMvc.perform() directamente.
     */
    protected ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult mvcResult = mockMvc.perform(request).andReturn();
        return mockMvc.perform(asyncDispatch(mvcResult));
    }

    /**
     * Configura el mock de UserRepository para que el usuario dado sea dueño
     * de los gateways indicados. Llamar en @BeforeEach o al inicio del test.
     */
    protected void declareOwnership(String username, String... gatewayIds) {
        List<Gateway> gateways = Arrays.stream(gatewayIds)
                .map(gwId -> {
                    Gateway gw = Mockito.mock(Gateway.class);
                    Mockito.when(gw.getId()).thenReturn(gwId);
                    return gw;
                })
                .toList();
        User user = Mockito.mock(User.class);
        Mockito.when(user.getGateways()).thenReturn(gateways);
        Mockito.when(userRepository.findByUsernameWithGateways(username))
                .thenReturn(Optional.of(user));
    }

    protected String bearerToken(String username, List<String> gatewayIds) {
        return "Bearer " + jwtService.generateToken(
                username,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }

    /** HashMap mutable — necesario para AdminController que muta el map devuelto */
    protected static Map<String, Object> mutableMap(Object... keysAndValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
