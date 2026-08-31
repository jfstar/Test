# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Etapa 1: build. Usa una imagen con Maven + JDK 17 solo para compilar; no
# viaja al artefacto final, por eso el resultado (etapa 2) es liviano.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copiar primero solo el pom.xml permite a Docker cachear las dependencias
# descargadas mientras el codigo fuente no cambie.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---------------------------------------------------------------------------
# Etapa 2: runtime. Imagen liviana (solo JRE) y usuario no-root.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app

COPY --from=build /build/target/microservicio-cuentas.jar app.jar
RUN chown spring:spring app.jar
USER spring

EXPOSE 8080

# Toda la configuracion (perfil, credenciales de BD, URL del tercero) llega
# por variables de entorno en tiempo de ejecucion (ver application.yml y
# docker-compose.yml) -- nada de secretos hardcodeados en la imagen.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
