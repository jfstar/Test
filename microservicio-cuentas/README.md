# Microservicio de Cuentas y Movimientos

Prueba técnica — Desarrollador Java Senior. Este README cubre lo siguiente:
**Tarea 1** (microservicio Spring Boot + API REST)
**Tarea 2**(capa de datos y T-SQL en SQL Server)
**Tarea 3** (resiliencia e integración con terceros vía Resilience4j)
**Tarea 4** (pruebas automatizadas)
**Tarea 5 bonus** (observabilidad: correlation-id, logs estructurados,métricas Prometheus)
además del Dockerfile del servicio.

## Stack

- Java 17 (compilado con JDK 21, `--release 17`), Spring Boot 3.3.4
- Spring Web, Spring Data JPA, Bean Validation, Spring Boot Actuator
- springdoc-openapi (Swagger UI)
- H2 (perfil por defecto `h2`) / SQL Server (perfil `sqlserver`, para la Tarea 2)
- Lombok
- Resilience4j (timeout de cliente HTTP + retry + circuit breaker + bulkhead,
  Tarea 3) sobre `RestClient` para el servicio externo de validación
- JUnit 5, Mockito, AssertJ (vía `spring-boot-starter-test`) y WireMock
  embebido (`org.wiremock:wiremock-standalone`, solo `test`) para la Tarea 4
- Micrometer + Prometheus (`micrometer-registry-prometheus`) y logs JSON
  estructurados con correlation-id (`logstash-logback-encoder`) para la
  Tarea 5
- Maven

## Cómo ejecutar

Requisitos: JDK 17+ y Maven (o usa el wrapper si se agrega uno).

```bash
cd microservicio-cuentas
mvn spring-boot:run
```

La app arranca en `http://localhost:8080` con el perfil `h2` (base de datos en memoria,
no requiere Docker ni SQL Server instalado). Documentación interactiva:

- Swagger UI: http://localhost:8080/swagger-ui/index.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs (también exportado como
  [`openapi.json`](./openapi.json) en la raíz del proyecto)
- Health check: http://localhost:8080/actuator/health

Para compilar y correr las pruebas:

```bash
mvn clean verify
```

### Perfil SQL Server (Tarea 2)

El perfil `sqlserver` conecta contra una base real usando variables de entorno (sin
credenciales en el código). En este perfil `ddl-auto=validate`: **el esquema debe
existir previamente** aplicando los scripts de [`../sql-server`](../sql-server), la
app no lo crea.

**Opción A — todo con Docker (recomendada, ver sección Docker más abajo):**
```bash
cd ..                     # raíz del repo, junto a docker-compose.yml
cp .env.example .env      # y editar la contraseña
docker compose up --build
```
Esto levanta SQL Server, aplica `schema.sql` + `sp_estado_cuenta.sql` +
`datos_prueba.sql` automáticamente, y arranca la app ya apuntando a esa base.

**Opción B — SQL Server ya corriendo en otro lado:**
```bash
# aplicar manualmente, en este orden:
sqlcmd -S <host> -U sa -P '<password>' -i ../sql-server/schema.sql
sqlcmd -S <host> -U sa -P '<password>' -i ../sql-server/sp_estado_cuenta.sql
sqlcmd -S <host> -U sa -P '<password>' -i ../sql-server/datos_prueba.sql

export SPRING_PROFILES_ACTIVE=sqlserver
export DB_URL="jdbc:sqlserver://<host>:1433;databaseName=CuentasDB;encrypt=true;trustServerCertificate=true"
export DB_USERNAME=sa
export DB_PASSWORD='<password>'
mvn spring-boot:run
```

## Endpoints implementados

| Método | Ruta | Descripción | Códigos |
|---|---|---|---|
| POST | `/accounts` | Crea una cuenta (saldo inicial ≥ 0) | 201, 400 |
| GET | `/accounts/{id}` | Consulta cuenta y saldo actual | 200, 404 |
| POST | `/accounts/{id}/transactions` | Registra CREDIT/DEBIT, idempotente, valida contra un tercero externo | 201, 200 (replay), 400, 404, 409, 422, 503 |
| GET | `/actuator/health` | Health check | 200 |

### Ejemplos

**Crear cuenta**
```http
POST /accounts
Content-Type: application/json

{ "titular": "Maria Perez", "saldoInicial": 100.00 }
```
```json
201 Created
{
  "id": "1dc3e5bb-7326-4faf-a3e5-02187a2fda3a",
  "titular": "Maria Perez",
  "saldo": 100.0000,
  "fechaCreacion": "2026-08-29T01:20:16.506337Z"
}
```

**Registrar un débito (con idempotencia)**
```http
POST /accounts/1dc3e5bb-7326-4faf-a3e5-02187a2fda3a/transactions
Content-Type: application/json
Idempotency-Key: key-1

{ "tipo": "DEBIT", "monto": 30.00 }
```
```json
201 Created
{
  "id": "a67315c3-51f3-47b8-b0ee-751ab3ba1bf5",
  "cuentaId": "1dc3e5bb-7326-4faf-a3e5-02187a2fda3a",
  "tipo": "DEBIT",
  "monto": 30.00,
  "saldoResultante": 70.0000,
  "fecha": "2026-08-29T01:20:16.855603Z",
  "idempotencyKey": "key-1",
  "replay": false
}
```

Repetir exactamente la misma petición con `Idempotency-Key: key-1` devuelve **200 OK**
con el mismo movimiento (`"replay": true`) y **no crea un segundo movimiento ni vuelve
a debitar el saldo**.

**Débito que excede el saldo**
```json
409 Conflict
{
  "timestamp": "2026-08-29T01:20:16.973Z",
  "status": 409,
  "error": "Conflict",
  "message": "Saldo insuficiente en cuenta 1dc3e5bb-...: saldo=70.0000, monto solicitado=9999.00",
  "path": "/accounts/1dc3e5bb-.../transactions"
}
```

**Cuenta inexistente**
```json
404 Not Found
{ "status": 404, "error": "Not Found", "message": "No existe una cuenta con id ...", ... }
```

**Entrada inválida**
```json
400 Bad Request
{ "status": 400, "detalles": ["titular: titular es obligatorio", "saldoInicial: saldoInicial no puede ser negativo"] }
```

**El tercero rechaza el movimiento** (validación de negocio, ver Tarea 3)
```json
422 Unprocessable Entity
{
  "status": 422,
  "error": "Unprocessable Entity",
  "message": "El movimiento sobre la cuenta ... fue rechazado por el servicio de validacion: Monto senalado para revision manual ..."
}
```

**El tercero está caído** (circuit breaker abierto o reintentos agotados, ver Tarea 3)
```json
503 Service Unavailable
{
  "status": 503,
  "error": "Service Unavailable",
  "message": "El servicio de validacion de terceros no esta disponible en este momento."
}
```

Estos escenarios fueron verificados manualmente contra la app corriendo en local
(arranque real, no solo tests) antes de dar la tarea por cerrada, tanto contra H2 como
contra SQL Server real vía `docker compose up`. Los escenarios de validación externa
(422 y 503, Tarea 3) se detallan y verifican en la sección
[Resiliencia e integración con terceros](#resiliencia-e-integración-con-terceros-tarea-3)
más abajo.

## Capa de datos y T-SQL (Tarea 2)

Los scripts viven en [`../sql-server`](../sql-server) y se aplican en este orden:

1. **`schema.sql`** — crea la base `CuentasDB` y las tablas `cuenta` / `movimiento`
   con llaves, `CHECK` y los índices que respaldan las reglas de negocio: `DECIMAL(19,4)`
   para dinero, `UNIQUEIDENTIFIER` para los IDs, y un **índice único filtrado**
   `(cuenta_id, idempotency_key) WHERE idempotency_key IS NOT NULL` como segunda línea
   de defensa de la idempotencia (la primera es el bloqueo pesimista en el service).
2. **`sp_estado_cuenta.sql`** — procedimiento `dbo.sp_estado_cuenta` que devuelve el
   estado de cuenta paginado (`OFFSET/FETCH`) con saldo corriente y `total_registros`
   vía `COUNT(*) OVER()`, filtrable por rango de fechas.
3. **`datos_prueba.sql`** — dos cuentas con varios movimientos ficticios (para poder
   ejercitar paginación y filtro de fechas) y una cuenta sin movimientos (caso límite).

Los tres scripts fueron **ejecutados de verdad contra un contenedor SQL Server 2022**
(no solo revisados) — ver [`docker-compose.yml`](../docker-compose.yml) — y el SP fue
probado con `EXEC` cubriendo: paginación (3 páginas de tamaño 3), filtro por rango de
fechas, cuenta sin movimientos (0 filas, sin error) y cuenta inexistente (error de
negocio controlado, `THROW 50003`). El microservicio, corriendo con el perfil
`sqlserver`, validó el esquema contra Hibernate (`ddl-auto=validate`) sin ajustes
manuales, y se ejercitó el flujo completo de creación de cuenta + débito + idempotencia
contra esa misma base.

⚠️ Nota importante encontrada durante esa verificación (y ya corregida en los scripts):
las columnas de fecha son `DATETIMEOFFSET(6)`, no `DATETIME2`, porque Hibernate mapea
`java.time.Instant` a `TIMESTAMP_UTC` → `datetimeoffset(6)` en el dialecto de SQL
Server; y toda sesión que haga `INSERT`/`DELETE`/`UPDATE` sobre `movimiento` necesita
`SET QUOTED_IDENTIFIER ON` explícito (requerido por el índice único filtrado), ya que
cada conexión de `sqlcmd` arranca sin ese valor garantizado.

## Docker

### Solo la app (`Dockerfile`, multi-stage)

```bash
cd microservicio-cuentas
docker build -t microservicio-cuentas .
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=h2 \
  microservicio-cuentas
```

Build multi-stage: la etapa 1 compila con Maven+JDK17 (no viaja a la imagen final), la
etapa 2 corre solo con un JRE Alpine liviano y un usuario no-root. Ninguna variable
sensible está hardcodeada en la imagen; todo llega por `-e` / `environment:`.

### Stack completo (app + SQL Server) con `docker-compose.yml`

El compose vive en la raíz del repositorio (`../docker-compose.yml`, junto a
`sql-server/`):

```bash
cd ..                      # raíz del repo
cp .env.example .env       # definir MSSQL_SA_PASSWORD (no se commitea, ver .gitignore)
docker compose up --build
```

Servicios:
- **`sqlserver`** — SQL Server 2022 Developer (gratuita), con healthcheck.
- **`sqlserver-init`** — job de un solo uso que aplica los tres scripts de
  `sql-server/` contra `sqlserver` en cuanto está sana, y termina (`service_completed_successfully`).
- **`third-party-validator`** — stub WireMock del validador externo (Tarea 3).
- **`app`** — el microservicio, perfil `sqlserver`, arranca solo después de que la
  inicialización de la base termina con éxito.
- **`prometheus`** — Prometheus (Tarea 5, bonus), scrapea `app:8080/actuator/prometheus`
  cada 10s. UI en http://localhost:9090.

```bash
docker compose down       # detener
docker compose down -v    # detener y borrar también los datos de SQL Server y Prometheus
```

## Resiliencia e integración con terceros (Tarea 3)

Antes de confirmar cualquier movimiento, el service llama a un servicio externo
de validación (`POST /validate`). Como ese tercero no existe de verdad, se simula
con **WireMock** corriendo en un contenedor aparte ([`../third-party-stub`](../third-party-stub)),
que se puede apagar/prender de verdad para demostrar ambos comportamientos.

### Capas

```
AccountController → AccountService → ValidationClient (interfaz)
                                            │
                                            ▼
                                 RestValidationClient (adaptador)
                                   @CircuitBreaker @Retry @Bulkhead
                                            │
                                            ▼
                                RestClient (timeout de conexión/lectura)
                                            │
                                            ▼
                              tercero externo (WireMock / real)
```

El controller y el service **solo conocen la interfaz `ValidationClient`**; nunca
usan `RestClient`/`WebClient` directamente — toda la integración HTTP y la
resiliencia están aisladas en `adapter/validation/RestValidationClient`.

### Qué pasa en cada escenario

| Escenario | Qué responde el tercero | Qué hace la app | HTTP |
|---|---|---|---|
| Camino feliz | `200 {"aprobado": true}` | Aplica el movimiento | 201 |
| Rechazo de negocio | `200 {"aprobado": false, "motivo": "..."}` | Rechaza, **no** es un fallo de comunicación: no reintenta, no cuenta para el circuit breaker | 422 |
| Tercero lento/caído, reintentos agotados | timeout / conexión rechazada / 5xx | Reintenta (acotado), si sigue fallando aplica el fallback | 503 |
| Circuit breaker abierto | (no se llega a llamar) | Rechaza inmediatamente sin intentar la red | 503 |

En los dos últimos casos **el movimiento nunca se aplica** (no se toca el saldo ni
se inserta un `Movement`): la validación se llama después de la regla de saldo
local pero antes de persistir, dentro de la misma transacción.

### Decisión de negocio: fail-closed, no fail-open ni cola

Si el validador externo no responde, el movimiento se **rechaza** (503) en vez de:
- aplicarse sin validar (fail-open) — inaceptable si el validador existe para
  aplicar reglas de fraude/PLD antes de mover dinero, o
- encolarse para procesar después (patrón outbox/saga) — válido en general, pero
  agrega infraestructura (cola, worker, reconciliación) fuera del alcance de esta
  prueba y no es lo que un validador *previo* a confirmar (no un efecto posterior)
  necesita.

### Configuración de resiliencia (`application.yml`)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      validationService:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedNumberOfCallsInHalfOpenState: 3
  retry:
    instances:
      validationService:
        maxAttempts: 3
        waitDuration: 300ms
        retryExceptions:
          - com.pruebatecnica.accounts.adapter.validation.ValidationServiceException
  bulkhead:
    instances:
      validationService:
        maxConcurrentCalls: 10
```

- **Timeout**: no se usa el `TimeLimiter` de Resilience4j (pensado para llamadas
  asíncronas/`CompletableFuture`); esta integración es síncrona, así que el
  timeout se configura directamente en el `RestClient` (`connect-timeout-ms`,
  `read-timeout-ms`, variables `VALIDATION_CONNECT_TIMEOUT_MS` /
  `VALIDATION_READ_TIMEOUT_MS`).
- **Retry acotado**: solo 3 intentos con 300ms de espera, y **solo** para
  `ValidationServiceException` (fallas de comunicación). Un rechazo de negocio
  (`aprobado:false` con HTTP 200) nunca es una excepción, así que nunca se
  reintenta — reintentar una decisión de negocio ya tomada no tiene sentido y
  amplificaría carga sin cambiar el resultado.
- **Circuit breaker**: ventana de 10 llamadas, abre con ≥50% de fallos tras un
  mínimo de 5 llamadas, permanece abierto 10s y luego prueba 3 llamadas en
  `HALF_OPEN` antes de decidir si cierra o vuelve a abrir.
- **Bulkhead**: acota a 10 las llamadas concurrentes al validador, para que una
  ráfaga de tráfico no agote los hilos del servidor esperando a un tercero lento.
- **Gotcha de orden de aspectos** (documentado también en el Javadoc de
  `RestValidationClient`): el orden fijo de Resilience4j es
  `CircuitBreaker → Retry → Bulkhead → llamada real` (de afuera hacia adentro).
  Por eso el `fallbackMethod` se declara **solo** en `@CircuitBreaker`: si también
  se declarara en `@Retry`, el propio Retry devolvería el fallback como resultado
  "normal" y el CircuitBreaker (que lo envuelve) nunca vería el fallo — jamás
  abriría el circuito.

### Cómo demostrarlo tú mismo

Con `docker compose up --build` corriendo (ver sección Docker):

```bash
# 1) Camino feliz: crear cuenta y debitar con el tercero arriba
ID=$(curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' \
     -d '{"titular":"Demo","saldoInicial":1000}' | grep -oE '"id":"[^"]+"' | cut -d'"' -f4)
curl -i -X POST localhost:8080/accounts/$ID/transactions -H 'Content-Type: application/json' \
     -d '{"tipo":"DEBIT","monto":50}'          # -> 201

# 2) Rechazo de negocio del tercero (monto "magico" del stub, ver third-party-stub/)
curl -i -X POST localhost:8080/accounts/$ID/transactions -H 'Content-Type: application/json' \
     -d '{"tipo":"DEBIT","monto":666.66}'      # -> 422, saldo intacto

# 3) Apagar el tercero
docker compose stop third-party-validator

# unas pocas llamadas seguidas -> 503 (las primeras con reintentos reales, ~1s;
# tras abrir el circuito, 503 casi instantaneo sin tocar la red)
for i in 1 2 3 4 5 6; do
  curl -s -o /dev/null -w "%{http_code} en %{time_total}s\n" \
    -X POST localhost:8080/accounts/$ID/transactions -H 'Content-Type: application/json' \
    -d '{"tipo":"DEBIT","monto":10}'
done

# Confirmar el estado del circuit breaker
curl -s localhost:8080/actuator/circuitbreakers

# 4) Restaurar el tercero y ver que vuelve a cerrar
docker compose start third-party-validator
sleep 12   # esperar waitDurationInOpenState
curl -i -X POST localhost:8080/accounts/$ID/transactions -H 'Content-Type: application/json' \
     -d '{"tipo":"DEBIT","monto":10}'          # -> 201
curl -s localhost:8080/actuator/circuitbreakers
```

Este ciclo completo (`CLOSED → OPEN → HALF_OPEN → OPEN → CLOSED`) fue verificado
de verdad contra el stack de Docker antes de dar la tarea por cerrada, incluyendo
revisar `/actuator/circuitbreakerevents/validationService` para confirmar las
transiciones de estado.

## Pruebas automatizadas (Tarea 4)

### Cómo correrlas

Un solo comando, sin necesidad de Docker, SQL Server ni el stub de WireMock
levantado a mano — todo lo que hace falta corre embebido dentro del propio
proceso de test:

```bash
cd microservicio-cuentas
mvn test
```

(`mvn clean verify` corre lo mismo más el empaquetado del jar; `mvn clean test`
si además quieres forzar una recompilación limpia primero.) Salida esperada:

```
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Qué se cubrió

**Unitarias** (`src/test/.../service/AccountServiceImplTest.java`, Mockito puro,
sin contexto de Spring — rápidas):
- Regla de saldo insuficiente (débito que excede el saldo) y que en ese caso
  **ni siquiera se llama** al validador externo (regla local barata primero).
- Débito exacto al saldo disponible (caso límite: resulta en saldo 0, no falla).
- Idempotencia: una `Idempotency-Key` repetida devuelve el movimiento existente
  sin duplicar ni volver a llamar al validador; una clave en blanco se trata
  como si no se hubiese enviado.
- Camino feliz de CREDIT y DEBIT, verificando el saldo resultante y el
  `Movement` persistido (con `ArgumentCaptor`).
- Cuenta inexistente al consultar o al transaccionar.
- Rechazo del validador externo (`aprobado:false`) y tercero caído
  (`ThirdPartyUnavailableException`): en ambos casos se verifica que **no** se
  guarda ni la cuenta ni el movimiento.

**Validaciones** (`src/test/.../dto/RequestValidationTest.java`, Bean Validation
directo sobre los DTOs, sin contexto de Spring): titular en blanco, saldo
inicial negativo (y el límite exacto en 0, que sí es válido), tipo de
movimiento nulo, monto en cero o negativo.

**Integración** (`@SpringBootTest` + `MockMvc`, contra H2 real):
- `AccountApiIntegrationTest`: camino feliz (201), datos inválidos (400),
  cuenta inexistente (404), saldo insuficiente (409, y se verifica con
  `wireMockServer.verify(0, ...)` que el validador externo nunca recibió la
  llamada), idempotencia de punta a punta por HTTP (se verifica que WireMock
  solo recibió **1** llamada pese a 2 peticiones idénticas), y rechazo de
  negocio del validador externo (422).
- `ThirdPartyDownIntegrationTest`: **la prueba que demuestra el fallback ante
  el tercero caído** (criterio de aceptación de la Tarea 3/4). Apunta
  `app.validation.base-url` a un puerto localhost que se abre y se cierra
  inmediatamente antes de arrancar el contexto (ver comentario en la clase),
  garantizando "conexión rechazada" real y determinista sin depender de Docker
  ni de parar un proceso a mano. Verifica 503, que el cuerpo de la respuesta es
  el `ErrorResponse` controlado (no un stack trace ni el mensaje crudo de
  `ConnectException`), y que el saldo no se tocó.

Ambas pruebas de integración usan **WireMock embebido** (`org.wiremock:wiremock-standalone`,
scope `test`, no viaja al jar final) en vez de un mock en memoria del cliente
Java: el `RestValidationClient` hace una llamada HTTP real a un servidor real
corriendo en un puerto local, igual que en producción — es el mismo enfoque que
el stub de Docker de la Tarea 3, pero autocontenido en el proceso de test.

### Decisión de diseño: dos clases de integración, no una

`ThirdPartyDownIntegrationTest` vive separada de `AccountApiIntegrationTest` a
propósito. `@SpringBootTest` cachea el `ApplicationContext` entre métodos de
una misma clase, y con él el estado del `CircuitBreakerRegistry`: si las
pruebas de rechazo/caída compartieran contexto con las del camino feliz, una
podría dejar el circuit breaker abierto y arruinar silenciosamente una prueba
posterior que espera un 201. Cada clase usa una URL de validador distinta
(`@DynamicPropertySource`), lo que le da a Spring una clave de cache de
contexto distinta por clase — contextos (y por lo tanto circuit breakers)
completamente aislados, sin tener que resetear manualmente registries entre
tests.

### Qué se dejó fuera (y por qué)

- **No se agotó el circuit breaker hasta `OPEN` dentro de un test automatizado**
  (sí se verificó manualmente y a fondo en Docker, ver sección anterior):
  hacerlo de forma determinista requeriría ajustar `minimumNumberOfCalls` /
  `slidingWindowSize` solo para el test o inyectar el `CircuitBreakerRegistry`
  para forzar transiciones, lo que acopla el test a detalles de configuración
  de Resilience4j más de lo que vale la pena para esta entrega. Lo que sí se
  automatizó (503 + fallback controlado sin excepción cruda) es exactamente el
  criterio de aceptación pedido.
- **No se agregaron pruebas de concurrencia real** (dos hilos debitando la
  misma cuenta a la vez) para validar el bloqueo pesimista de la Tarea 1: es
  válido y demostrable, pero requiere infraestructura de test adicional
  (`ExecutorService` + sincronización de hilos) que no alcanzaba a justificarse
  en el tiempo de esta prueba. Documentado aquí como próximo paso.
- **No se probó el perfil `sqlserver`** desde `mvn test` (los tests corren
  contra H2): la Tarea 2 ya verificó el esquema y el SP contra SQL Server real
  vía Docker por separado; usar Testcontainers con SQL Server real para los
  tests de JUnit habría sido más fiel, pero agrega tiempo de arranque y una
  dependencia de Docker al comando `mvn test`, rompiendo el criterio de "un
  solo comando, sin nada más corriendo".

## Observabilidad y trazabilidad (Tarea 5, bonus)

### Correlation-id + logs estructurados

`CorrelationIdFilter` (`@Order(HIGHEST_PRECEDENCE)`) intercepta toda petición:
reutiliza la cabecera `X-Correlation-Id` si el cliente ya la envió (permite
encadenar el id entre varios servicios), o genera un `UUID` si no. El id:

- se pone en el MDC de SLF4J, así que aparece en **cada línea de log** emitida
  durante esa petición;
- se devuelve en la respuesta (misma cabecera), para que el cliente pueda
  correlacionar sus propios logs con los del servidor;
- se reenvía a la llamada saliente hacia el validador externo
  (`ValidationClientConfig`, `requestInterceptor`), para una traza end-to-end
  si ese tercero algún día también lo registra;
- se limpia del MDC en un `finally` (los hilos de Tomcat se reutilizan entre
  peticiones — sin este cleanup un id se "filtraría" a la siguiente petición
  atendida por el mismo hilo).

Además, el propio filtro deja **una línea de log garantizada por petición**
(método, URI, status, duración), independiente de si la lógica de negocio
loguea algo — así cualquier petición es rastreable por `correlationId`, no
solo las que tocan el validador externo.

Los logs salen en **JSON estructurado** (`logback-spring.xml`,
`logstash-logback-encoder`), no texto plano con timestamp pegado — cada campo
(`correlationId`, `level`, `logger`, `message`, `service`) es una clave
independiente, lista para cualquier sistema de recolección de logs (ELK,
Loki, CloudWatch) sin necesidad de parsear el mensaje.

**Demo:**
```bash
curl -sD - http://localhost:8080/accounts/<id>
# -> Header de respuesta: X-Correlation-Id: <uuid generado>

curl -sD - -H "X-Correlation-Id: mi-id-propio" http://localhost:8080/accounts/<id>
# -> Header de respuesta: X-Correlation-Id: mi-id-propio  (se reutilizo el enviado)

docker logs cuentas-app | grep '"correlationId":"mi-id-propio"'
# -> todas las lineas de log de esa peticion especifica
```

### Métricas con Micrometer / Prometheus

`/actuator/prometheus` expone en formato Prometheus todo lo que Micrometer ya
recolecta automáticamente: latencia HTTP por endpoint (con histograma —
`management.metrics.distribution.percentiles-histogram.http.server.requests=true`,
necesario para poder calcular p95/p99 en Prometheus, no solo un promedio),
estado y tasa de fallos del circuit breaker (Tarea 3), pool de conexiones
HikariCP, métricas de la JVM (heap, GC), etc. Todas las métricas llevan la
etiqueta `application=microservicio-cuentas` para poder distinguir servicios
si un mismo Prometheus observa varios.

**Prometheus corre en un contenedor separado** (`prometheus/prometheus`,
imagen oficial), agregado como servicio en `docker-compose.yml`, con su
configuración de scraping en [`../monitoring/prometheus.yml`](../monitoring/prometheus.yml)
(scrapea `app:8080/actuator/prometheus` cada 10s):

```bash
docker compose up --build
# Prometheus UI: http://localhost:9090
# Prometheus > Status > Targets debe mostrar "microservicio-cuentas" como UP
```

Verificado de verdad: con el stack corriendo, `http://localhost:9090/api/v1/targets`
muestra el target `up`, y consultas como
`http_server_requests_seconds_count`, `resilience4j_circuitbreaker_state` y
`hikaricp_connections_active` devuelven series reales generadas por tráfico
contra la API — no solo el endpoint `/actuator/prometheus` respondiendo, sino
Prometheus efectivamente scrapeándolo y almacenando los datos.

### Runbook — qué vigilar en producción

Indicadores clave (todos disponibles en `/actuator/prometheus`), y cómo se
conectan con el escenario de degradación del **Bloque B** (endpoint que iba a
~200ms empieza a tardar 2-3s de forma intermitente, sin errores en los logs
de la aplicación):

1. **Latencia HTTP por endpoint** — `http_server_requests_seconds` (bucket/
   sum/count), etiquetado por `uri`/`method`/`status`. Vigilar p95/p99 de
   `POST /accounts/{id}/transactions` en particular (es el único endpoint que
   llama a un tercero y toma un lock). Si sube *solo* ahí y no en
   `GET /accounts/{id}`, el problema está acotado a esa ruta de código, no es
   generalizado.

2. **Estado y llamadas lentas del circuit breaker** —
   `resilience4j_circuitbreaker_state{name="validationService"}` y
   `resilience4j_circuitbreaker_calls_seconds_count`. Un circuito que sigue
   `CLOSED` pero con `slow_call_rate` alto indica que el tercero responde,
   pero cada vez más lento — exactamente el síntoma "intermitente, sin
   errores" del Bloque B: no hay excepciones (por eso no aparecen en los logs
   de aplicación), pero sí llamadas lentas que Micrometer sí registra.

3. **Reintentos** — `resilience4j_retry_calls_total{kind="successful_with_retry"}`
   en aumento sostenido: el primer intento está fallando y se recupera al
   segundo, lo cual **tampoco genera una línea de ERROR** en los logs (el
   request termina en 201 igual) pero sí incrementa esta métrica — es
   justamente el tipo de señal que un runbook basado solo en logs se perdería.

4. **Pool de conexiones (HikariCP)** — `hikaricp_connections_active`,
   `hikaricp_connections_pending`, `hikaricp_connections_timeout_total`. Si
   "pending" sube mientras "active" está en el máximo configurado, sugiere que
   las transacciones tardan más en liberar su conexión — coherente con el
   lock pesimista de una cuenta retenido más tiempo del esperado mientras se
   espera al validador externo lento (ver la decisión documentada en Tarea 3
   sobre este trade-off).

5. **JVM** — `jvm_gc_pause_seconds_sum`, `jvm_memory_used_bytes` /
   `jvm_memory_max_bytes`. Sirve para descartar la causa antes de culpar al
   tercero: si las pausas de GC coinciden en el tiempo con los picos de
   latencia, el problema es presión de memoria en esta JVM, no el validador
   externo.

6. **Desglose de errores HTTP** — `http_server_requests_seconds_count` por
   `status`: diferenciar 503 (tercero caído) de 422 (tercero disponible pero
   rechaza) de 409 (regla de negocio local) de 500 (bug). Un runbook que solo
   cuenta "5xx" no distingue un circuit breaker funcionando correctamente
   (503 controlado) de un fallo real de la aplicación.

**Secuencia de diagnóstico sugerida** ante el síntoma del Bloque B: (1) aislar
el endpoint afectado con las métricas de latencia; (2) si es
`/transactions`, revisar `resilience4j_circuitbreaker_calls_seconds` para ver
si el tiempo extra coincide con la llamada al validador; (3) revisar Hikari
por contención de pool; (4) descartar GC; (5) usar el `correlation-id` de una
petición lenta puntual (reportada por un cliente, o encontrada por su
duración en los logs JSON) para reconstruir esa transacción específica de
punta a punta.

## Decisiones técnicas y por qué

- **ID de cuenta/movimiento como UUID en vez de autoincremental**: evita que un
  atacante enumere cuentas ajenas probando IDs secuenciales en un servicio financiero
  expuesto (`/accounts/1`, `/accounts/2`, ...). El costo (índices ligeramente más
  grandes, IDs no ordenables por fecha) es aceptable frente al riesgo evitado.

- **Idempotencia vía cabecera `Idempotency-Key` + bloqueo pesimista en la cuenta**:
  antes de aplicar el movimiento se adquiere `PESSIMISTIC_WRITE` sobre la fila de
  `Account` (`AccountRepository#findWithLockById`). Esto serializa cualquier
  concurrencia sobre la misma cuenta — incluyendo dos peticiones simultáneas con la
  misma `Idempotency-Key` — y hace que la comprobación "¿ya existe un movimiento con
  esta clave?" sea segura sin condiciones de carrera: la segunda petición espera el
  lock, y cuando lo obtiene ya encuentra el movimiento insertado por la primera.
  Alternativa descartada: confiar solo en una restricción `UNIQUE` en BD y capturar
  la excepción de duplicado; es válida pero más frágil de razonar y se documenta como
  segunda línea de defensa a nivel de esquema en la Tarea 2 (índice filtrado, porque
  la clave es opcional/nullable).

- **`BigDecimal` con `precision=19, scale=4` en vez de `double`/`float`**: los tipos de
  punto flotante binario no representan exactamente cantidades decimales (p. ej.
  `0.1 + 0.2 != 0.3`), lo cual es inaceptable para dinero. `DECIMAL(19,4)` es el
  estándar en el dominio financiero y se refleja igual en el esquema SQL Server de la
  Tarea 2.

- **`saldoResultante` (running balance) guardado en cada `Movement`**: permite que el
  estado de cuenta (Tarea 2, SP con paginación) no tenga que recalcular sumas
  acumuladas sobre todo el historial en cada consulta — se lee directamente, a costa
  de un pequeño espacio extra por fila.

- **Rechazo con 409 Conflict para saldo insuficiente** (no 400 ni 422): el request en
  sí es sintácticamente válido; lo que falla es el estado actual del recurso frente a
  la operación solicitada, que es la semántica que describe 409 en HTTP. Se
  distingue a propósito de dos códigos que la Tarea 3 introduce para el mismo
  endpoint: 422 (el tercero externo evaluó la operación y la rechazó — un
  problema semántico, no de estado) y 503 (no se pudo evaluar en absoluto porque
  el tercero está caído — un problema de disponibilidad, no del request).

- **`@ControllerAdvice` centralizado (`GlobalExceptionHandler`)**: separa el mapeo
  excepción → código HTTP de la lógica de negocio y evita filtrar stack traces o
  mensajes internos al cliente (el handler genérico de `Exception` devuelve un mensaje
  fijo, nunca `ex.getMessage()` de una excepción no controlada).

- **Perfiles Spring `h2` (default) y `sqlserver`**: permite que cualquiera clone el
  repo y lo arranque sin instalar SQL Server ni Docker, cumpliendo la nota de la
  Tarea 2 ("la app puede quedar contra H2, pero la capa T-SQL se entrega igual").
  Ninguna credencial vive en `application.yml`; el perfil `sqlserver` las toma de
  variables de entorno (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`).

- **Lombok**: reduce boilerplate de getters/setters/constructores en entidades y DTOs;
  se excluye del jar final (`spring-boot-maven-plugin` con `<excludes>`) porque es solo
  una herramienta de anotaciones en tiempo de compilación.

- **DTOs separados de las entidades JPA**: evita exponer columnas internas (p. ej.
  `version` de bloqueo optimista) en el contrato público y desacopla el modelo de
  persistencia del modelo de API.

- **Índice único filtrado en vez de `UNIQUE` simple para idempotencia**: la clave de
  idempotencia es opcional (puede no venir en la petición). Un `UNIQUE` estándar en
  SQL Server permite como máximo una fila con `NULL`, así que la segunda transacción
  sin `Idempotency-Key` ya fallaría. El índice filtrado
  (`WHERE idempotency_key IS NOT NULL`) deja convivir cuantas filas sin clave se
  necesiten y sigue forzando unicidad real donde sí importa.

- **`saldo_resultante` como fuente del saldo corriente en el SP, no una ventana
  `SUM() OVER()` recalculada**: el microservicio ya persiste ese valor en cada
  movimiento (Tarea 1); recalcularlo en el SP duplicaría trabajo y, peor, podría
  divergir del valor que la app usó para decidir si un débito era válido. Se prefirió
  leer el dato ya materializado antes que "demostrar" una técnica de T-SQL que
  introduce una segunda fuente de verdad para el mismo número.

- **`COUNT(*) OVER()` para el total de registros en vez de una segunda consulta**:
  evita una ida extra a la base solo para poder calcular el número de páginas en el
  cliente; el costo adicional es marginal porque ya se está escaneando el mismo
  conjunto de filas para la página.

## Qué falta / próximos pasos (fuera del alcance de esta entrega)

- Grafana como capa de visualización sobre Prometheus (dashboards): no se
  agregó porque el enunciado pide específicamente Micrometer/Prometheus, y
  Grafana es puramente aditivo — Prometheus ya permite consultar y graficar
  con su propia UI (`/graph`). Agregar un dashboard predefinido sería el
  siguiente paso natural.
- Pruebas de concurrencia real (varios hilos debitando la misma cuenta) y
  circuit breaker forzado hasta `OPEN` dentro de un test automatizado — ver el
  detalle de por qué se dejaron fuera en "Pruebas automatizadas (Tarea 4)".
- Con más tiempo: la validación externa se llama con el lock pesimista de la
  cuenta ya adquirido (ver Tarea 1), lo que mantiene el lock tomado durante toda
  la llamada HTTP. Para este alcance es aceptable porque el timeout está acotado
  (1-2s) y los reintentos son pocos, pero en un sistema de mayor throughput
  convendría validar *antes* de tomar el lock y re-verificar el saldo después
  (patrón optimista + doble chequeo) para no bloquear la cuenta mientras se
  espera a un tercero lento.

## Estructura del proyecto

```
microservicio-cuentas/
├── pom.xml
├── Dockerfile                  # build multi-stage (Tarea transversal)
├── .dockerignore
├── openapi.json                # spec exportada de /v3/api-docs
├── src/main/java/com/pruebatecnica/accounts/
│   ├── AccountsServiceApplication.java
│   ├── adapter/validation/     # Tarea 3: ValidationClient, RestValidationClient, DTOs
│   ├── config/                 # OpenApiConfig, ValidationClientConfig, CorrelationIdFilter (Tarea 5)
│   ├── controller/AccountController.java
│   ├── dto/                    # CreateAccountRequest, AccountResponse, ...
│   ├── exception/               # AccountNotFoundException, InsufficientBalanceException, MovementRejectedException, ThirdPartyUnavailableException, GlobalExceptionHandler
│   ├── model/                  # Account, Movement, MovementType (JPA)
│   ├── repository/             # AccountRepository, MovementRepository
│   └── service/                # AccountService (+ impl)
├── src/main/resources/
│   ├── application.yml          # incluye config de Resilience4j (Tarea 3) y Micrometer/Prometheus (Tarea 5)
│   └── logback-spring.xml       # logs JSON estructurados (Tarea 5)
└── src/test/java/com/pruebatecnica/accounts/   # Tarea 4
    ├── AccountsServiceApplicationTests.java     # smoke test
    ├── service/AccountServiceImplTest.java      # unitarias (Mockito)
    ├── dto/RequestValidationTest.java           # unitarias (Bean Validation)
    └── integration/
        ├── AccountApiIntegrationTest.java       # @SpringBootTest + MockMvc + WireMock embebido
        └── ThirdPartyDownIntegrationTest.java   # fallback ante tercero caido (503)
```

En la raíz del repositorio (un nivel arriba de esta carpeta) viven además:

```
prueba-tecnica/
├── docker-compose.yml     # orquesta sqlserver + sqlserver-init + third-party-validator + app + prometheus
├── .env.example           # plantilla de MSSQL_SA_PASSWORD (no committear .env)
├── sql-server/             # Tarea 2: schema.sql, sp_estado_cuenta.sql, datos_prueba.sql
├── third-party-stub/       # Tarea 3: mappings de WireMock para /validate
├── monitoring/             # Tarea 5: prometheus.yml (config de scraping)
└── microservicio-cuentas/  # este directorio
```
