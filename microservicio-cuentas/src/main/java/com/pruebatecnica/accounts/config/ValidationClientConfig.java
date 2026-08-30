package com.pruebatecnica.accounts.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP hacia el servicio externo de validacion (Tarea 3). Se
 * construye a partir del RestClient.Builder autoconfigurado por Spring
 * Boot para reutilizar sus conversores Jackson ya configurados, en vez de
 * uno "pelado" con RestClient.builder().
 */
@Configuration
public class ValidationClientConfig {

    @Bean
    public RestClient validationRestClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.validation.base-url}") String baseUrl,
            @Value("${app.validation.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${app.validation.read-timeout-ms}") int readTimeoutMs) {

        // El timeout se aplica aqui (a nivel de cliente HTTP), no con el
        // TimeLimiter de Resilience4j: TimeLimiter esta pensado para
        // llamadas asincronas (CompletableFuture); esta integracion es
        // sincrona/bloqueante, asi que el timeout de conexion/lectura del
        // propio cliente HTTP es la forma correcta de acotarla.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);

        return restClientBuilder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                // Propaga el correlation-id de la peticion entrante (ver
                // CorrelationIdFilter) hacia el tercero, para poder
                // reconstruir una traza end-to-end si el validador real
                // algun dia tambien lo registra en sus logs.
                .requestInterceptor((req, body, execution) -> {
                    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
                    if (StringUtils.hasText(correlationId)) {
                        req.getHeaders().add(CorrelationIdFilter.HEADER_NAME, correlationId);
                    }
                    return execution.execute(req, body);
                })
                .build();
    }
}
