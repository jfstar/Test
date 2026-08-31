# Stub del servicio externo de validación

Simula el `POST /validate` de terceros usando [WireMock](https://wiremock.org/)
(open-source, gratuito), corriendo como contenedor **separado** del microservicio
para poder apagarlo/encenderlo de verdad y así demostrar ambos comportamientos de
resiliencia (Tercero disponible / Tercero caído).

## Mappings

- **`mappings/validate-approved.json`** (prioridad 10, catch-all): cualquier
  `POST /validate` responde `200 { "aprobado": true, "motivo": "..." }`.
- **`mappings/validate-rejected.json`** (prioridad 1, más específico): si el
  cuerpo de la petición contiene el monto `666.66`, responde
  `200 { "aprobado": false, "motivo": "..." }` — simula un rechazo de negocio
  del tercero (distinto de que el tercero esté caído).

WireMock evalúa primero el mapping de **menor** número de prioridad, así que el
de rechazo (prioridad 1) se comprueba antes que el catch-all (prioridad 10).

## Cómo levantarlo

**Con el docker-compose de la raíz (recomendado)** — ya incluido como servicio
`third-party-validator`:
```bash
docker compose up --build
```

**Standalone**, para usarlo con `mvn spring-boot:run` fuera de docker-compose:
```bash
docker run --rm -p 8081:8080 \
  -v "$(pwd)/third-party-stub/mappings:/home/wiremock/mappings:ro" \
  wiremock/wiremock:3.9.1-alpine
```
El microservicio ya apunta por defecto a `http://localhost:8081` (variable
`VALIDATION_SERVICE_URL`, ver `application.yml`), así que con esto arriba el
camino feliz queda disponible sin configurar nada más.

## Cómo demostrar "tercero caído"

Con el stub apagado (o parado a mitad de una demo):
```bash
docker compose stop third-party-validator
```
Las siguientes llamadas a `POST /accounts/{id}/transactions` van a agotar los
reintentos configurados y, tras el umbral configurado, el circuit breaker se
abre — ver la sección "Tarea 3" del README de `microservicio-cuentas` para el
paso a paso completo con `curl`.

Para volver al camino feliz:
```bash
docker compose start third-party-validator
```
