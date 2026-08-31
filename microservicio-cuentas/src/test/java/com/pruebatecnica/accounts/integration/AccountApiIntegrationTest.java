package com.pruebatecnica.accounts.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueba de integracion de punta a punta: HTTP -> controller -> service ->
 * JPA (H2) -> adaptador de validacion externa (WireMock embebido, corriendo
 * de verdad en un puerto local, no un mock en memoria del cliente Java).
 *
 * Cubre el camino feliz y los casos limite pedidos por la Tarea 4: entrada
 * invalida (400), cuenta inexistente (404), saldo insuficiente (409) e
 * idempotencia. El caso "tercero caido" (503) vive en su propia clase,
 * ThirdPartyDownIntegrationTest, para no compartir el estado del circuit
 * breaker con estas pruebas del camino feliz.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountApiIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @DynamicPropertySource
    static void overrideValidationServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("app.validation.base-url", () -> "http://localhost:" + wireMockServer.port());
    }

    @BeforeEach
    void resetStub() {
        wireMockServer.resetAll();
        // Stub por defecto: el tercero aprueba cualquier movimiento, salvo
        // que un test individual registre un stub mas especifico (con mayor
        // prioridad numerica menor) para simular un rechazo puntual.
        wireMockServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/validate"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"aprobado\": true, \"motivo\": \"OK (stub de test)\"}")));
    }

    private UUID crearCuenta(String titular, String saldoInicial) throws Exception {
        String json = "{\"titular\":\"" + titular + "\",\"saldoInicial\":" + saldoInicial + "}";
        MvcResult result = mockMvc.perform(post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get("id").asText());
    }

    @Test
    void crearCuenta_caminoFeliz_devuelve201ConSaldoInicial() throws Exception {
        mockMvc.perform(post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"titular\":\"Maria Perez\",\"saldoInicial\":100.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.titular", is("Maria Perez")))
                .andExpect(jsonPath("$.saldo", is(100.00)));
    }

    @Test
    void crearCuenta_datosInvalidos_devuelve400ConDetallePorCampo() throws Exception {
        mockMvc.perform(post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"titular\":\"\",\"saldoInicial\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.detalles").isArray());
    }

    @Test
    void consultarCuenta_inexistente_devuelve404() throws Exception {
        mockMvc.perform(get("/accounts/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }

    @Test
    void debito_queExcedeSaldo_devuelve409_yNuncaLlamaAlValidadorExterno() throws Exception {
        UUID id = crearCuenta("Cuenta Debito Excesivo", "50.00");

        mockMvc.perform(post("/accounts/" + id + "/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"DEBIT\",\"monto\":999.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)));

        wireMockServer.verify(0, postRequestedFor(urlPathEqualTo("/validate")));
    }

    @Test
    void debito_conIdempotencyKeyRepetida_noDuplicaElMovimientoNiLlamaDosVecesAlValidador() throws Exception {
        UUID id = crearCuenta("Cuenta Idempotente", "100.00");
        String idempotencyKey = "test-key-" + UUID.randomUUID();
        String transaccion = "{\"tipo\":\"DEBIT\",\"monto\":30.00}";

        mockMvc.perform(post("/accounts/" + id + "/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content(transaccion))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replay", is(false)));

        mockMvc.perform(post("/accounts/" + id + "/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .content(transaccion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay", is(true)));

        mockMvc.perform(get("/accounts/" + id))
                .andExpect(jsonPath("$.saldo", is(70.00)));

        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/validate")));
    }

    @Test
    void debito_rechazadoPorElValidadorExterno_devuelve422_yNoAplicaElMovimiento() throws Exception {
        UUID id = crearCuenta("Cuenta Rechazo Validador", "500.00");

        // Stub especifico (mas prioritario) SOLO para este monto: simula que
        // el tercero, estando disponible, rechaza el movimiento por una
        // razon de negocio propia (distinto de "tercero caido").
        wireMockServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/validate"))
                .atPriority(1)
                .withRequestBody(containing("123.45"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"aprobado\": false, \"motivo\": \"Rechazado por regla de negocio (test)\"}")));

        mockMvc.perform(post("/accounts/" + id + "/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"DEBIT\",\"monto\":123.45}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)));

        mockMvc.perform(get("/accounts/" + id))
                .andExpect(jsonPath("$.saldo", is(500.00)));
    }

    @Test
    void credito_aprobado_actualizaElSaldo() throws Exception {
        UUID id = crearCuenta("Cuenta Credito", "10.00");

        mockMvc.perform(post("/accounts/" + id + "/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tipo\":\"CREDIT\",\"monto\":15.50}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saldoResultante", is(25.50)));
    }
}
