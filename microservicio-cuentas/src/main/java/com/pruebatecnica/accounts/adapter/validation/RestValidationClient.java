package com.pruebatecnica.accounts.adapter.validation;

import com.pruebatecnica.accounts.exception.ThirdPartyUnavailableException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Unico punto de la aplicacion que sabe que el validador externo se llama
 * por HTTP. El controller y el service solo conocen ValidationClient.
 *
 * Orden de aspectos de Resilience4j (fijo por la libreria, no por el orden
 * de las anotaciones en el codigo): CircuitBreaker (mas externo) envuelve a
 * Retry, que envuelve a Bulkhead (mas interno), que envuelve a la llamada
 * real. Por eso el fallbackMethod se declara SOLO en @CircuitBreaker: si
 * tambien se declarara en @Retry, el propio Retry "absorberia" el fallo
 * final devolviendo un resultado normal, y el CircuitBreaker nunca se
 * enteraria de que la llamada fallo -- nunca contabilizaria fallos y jamas
 * abriria el circuito.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RestValidationClient implements ValidationClient {

    private static final String RESILIENCE_INSTANCE = "validationService";

    private final RestClient validationRestClient;

    @Override
    @CircuitBreaker(name = RESILIENCE_INSTANCE, fallbackMethod = "fallback")
    @Retry(name = RESILIENCE_INSTANCE)
    @Bulkhead(name = RESILIENCE_INSTANCE)
    public ValidationResult validate(ValidationRequestDto request) {
        try {
            ValidationResult result = validationRestClient.post()
                    .uri("/validate")
                    .body(request)
                    .retrieve()
                    .body(ValidationResult.class);

            if (result == null) {
                throw new ValidationServiceException("Respuesta vacia del servicio de validacion externo");
            }
            return result;
        } catch (RestClientException ex) {
            // Cubre timeouts, conexion rechazada/reiniciada, y 4xx/5xx del
            // tercero (HttpClientErrorException/HttpServerErrorException
            // tambien son RestClientException). Es la unica excepcion que
            // Resilience4j reintenta (ver application.yml).
            throw new ValidationServiceException(
                    "Fallo al invocar el servicio de validacion externo: " + ex.getMessage(), ex);
        }
    }

    /**
     * Se invoca cuando: (a) se agotaron los reintentos y la ultima llamada
     * sigue fallando, o (b) el circuit breaker esta OPEN y rechaza la
     * llamada sin siquiera intentarla (CallNotPermittedException). En
     * ambos casos la decision de negocio es la misma: rechazar el
     * movimiento de forma controlada (fail-closed) en vez de aplicarlo sin
     * validar o dejarlo en un estado ambiguo.
     */
    @SuppressWarnings("unused")
    private ValidationResult fallback(ValidationRequestDto request, Throwable t) {
        log.warn(
                "Fallback de validacion activado para cuenta {} (causa: {}): se rechaza el movimiento de forma controlada.",
                request.getCuentaId(), t.toString());
        throw new ThirdPartyUnavailableException(
                "El servicio de validacion de terceros no esta disponible en este momento.", t);
    }
}
