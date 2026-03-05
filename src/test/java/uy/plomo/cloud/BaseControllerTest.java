package uy.plomo.cloud;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import uy.plomo.cloud.security.JwtService;
import uy.plomo.cloud.services.DynamoDBService;
import uy.plomo.cloud.services.MqttService;
import uy.plomo.cloud.services.PortPoolService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
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
        "iot.instanceName=test-instance"
})
public abstract class BaseControllerTest {

    @MockitoBean protected MqttService mqttService;
    @MockitoBean protected DynamoDBService dynamoDBService;
    @MockitoBean protected PortPoolService portPoolService;

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
     *   1. perform()        → inicia el request, Spring arranca el async processing
     *   2. asyncDispatch()  → espera que el CompletableFuture se resuelva y escribe
     *                         el status/body/headers reales en el response
     *
     * Sin el segundo paso, MockMvc devuelve un response vacío con status incorrecto
     * antes de que el controller haya terminado.
     *
     * Usar perform() de la base para todos los controllers async.
     * Para respuestas síncronas del filter chain (403, 404 del ownership filter),
     * usar mockMvc.perform() directamente desde el test.
     */
    protected ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult mvcResult = mockMvc.perform(request).andReturn();
        return mockMvc.perform(asyncDispatch(mvcResult));
    }

    protected String bearerToken(String username, List<String> gatewayIds) {
        return "Bearer " + jwtService.generateToken(
                username,
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                gatewayIds
        );
    }

    /** HashMap mutable -- necesario para mocks de AdminController que muta el mapa devuelto. */
    protected static Map<String, Object> mutableMap(Object... keysAndValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}