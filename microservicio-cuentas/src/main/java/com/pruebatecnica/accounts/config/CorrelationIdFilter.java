package com.pruebatecnica.accounts.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Propaga un correlation-id por toda la peticion (Tarea 5, bonus): si el
 * cliente ya envio la cabecera {@value #HEADER_NAME}, se reutiliza (permite
 * encadenar el id a traves de varios servicios); si no, se genera uno nuevo.
 *
 * El id se pone en el MDC de SLF4J para que aparezca en cada linea de log de
 * la peticion (ver logback-spring.xml, que lo serializa como campo JSON), y
 * se devuelve en la respuesta para que el cliente pueda correlacionar sus
 * propios logs con los del servidor.
 *
 * @Order(HIGHEST_PRECEDENCE) para que el id quede disponible en el MDC antes
 *                            de que corra cualquier otro filtro/interceptor
 *                            (incluida la resolucion de
 *                            excepciones del GlobalExceptionHandler).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_NAME);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Una linea de log garantizada por peticion (independiente de si
            // la logica de negocio loguea algo): permite reconstruir por
            // correlation-id que paso con CUALQUIER peticion, no solo las
            // que tocan el validador externo u otro punto que ya logueaba.
            long durationMs = System.currentTimeMillis() - start;
            log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), durationMs);
            // Imprescindible: los hilos de Tomcat se reutilizan entre
            // peticiones, asi que sin este cleanup un correlation-id se
            // "filtraria" a los logs de la siguiente peticion atendida por
            // el mismo hilo.
            MDC.remove(MDC_KEY);
        }
    }
}
