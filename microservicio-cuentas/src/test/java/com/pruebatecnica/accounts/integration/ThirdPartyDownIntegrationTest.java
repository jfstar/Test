package com.pruebatecnica.accounts.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Demuestra el comportamiento ante "tercero caido"
 * apunta app.validation.base-url a un puerto
 * localhost que se abre y se cierra inmediatamente antes de que Spring
 * arranque, garantizando "conexion rechazada" real y determinista en cada
 * intento -- sin depender de Docker ni de parar un proceso a mano.
 *
 * Vive en su propia clase (con su propio ApplicationContext, ver el
 * comentario sobre @DynamicPropertySource mas abajo) para que el estado del
 * circuit breaker de esta prueba nunca contamine las pruebas del camino
 * feliz en AccountApiIntegrationTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ThirdPartyDownIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void pointValidationServiceAtAClosedPort(DynamicPropertyRegistry registry) throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        // El socket ya se cerro al salir del try-with-resources: nada escucha
        // en este puerto, así que cualquier intento de conexión falla de
        // inmediato con "connection refused", sin esperar ningun timeout.
        // Como el valor de esta propiedad es distinto al de las demas clases
        // de test (cada una usa un puerto/WireMock distinto), Spring no
        // reutiliza el ApplicationContext entre clases: esta prueba obtiene
        // su propio CircuitBreakerRegistry, siempre arrancando en CLOSED.
        registry.add("app.validation.base-url", () -> "http://localhost:" + closedPort);
    }

    private UUID crearCuenta(String saldoInicial) throws Exception {
        MvcResult result = mockMvc.perform(post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"titular\":\"Cuenta Tercero Caido\",\"saldoInicial\":" + saldoInicial + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get("id").asText());
    }

    @Test
    void debito_conElTerceroCaido_devuelve503_sinPropagarLaExcepcionCruda_yNoAplicaElMovimiento() throws Exception {
        UUID id = crearCuenta("100.00");

        MvcResult result = mockMvc.perform(post("/accounts/" + id + "/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"DEBIT\",\"monto\":10.00}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status", is(503)))
                .andReturn();

        // La respuesta debe ser el ErrorResponse controlado del
        // GlobalExceptionHandler, no un stack trace ni el mensaje crudo de
        // RestClientException/ConnectException.
        String body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("ConnectException")
                .doesNotContain("com.pruebatecnica.accounts.adapter.validation.ValidationServiceException");

        // El movimiento nunca se aplico: el saldo sigue intacto.
        mockMvc.perform(get("/accounts/" + id))
                .andExpect(jsonPath("$.saldo", is(100.00)));
    }
}
