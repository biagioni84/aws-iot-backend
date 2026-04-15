# --- ETAPA DE CONSTRUCCIÓN ---
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY . .

# Build con daemon apagado para contenedores
RUN ./gradlew clean build -x test --no-daemon

# --- ETAPA DE RUNTIME ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 1. ACTUALIZACIÓN DE SEGURIDAD (Esto mata la mayoría de las vulnerabilidades del OS)
# Aprovechamos a instalar 'du' si no estuviera, aunque en Alpine ya viene.
RUN apk update && apk upgrade --no-cache && apk add --no-cache lsof

# 2. Copiamos los artefactos
COPY --from=builder /app/build/libs/lib/ ./lib/
COPY --from=builder /app/build/libs/*-lean.jar app.jar

# 3. AUDITORÍA DE PESO (Modificada para no dejar basura)
# Ejecutamos el du y luego borramos archivos temporales si los hubiera
RUN echo "Resumen de peso en /app:" && du -sh ./* && \
    echo "Top 10 librerías más pesadas:" && du -sh ./lib/* | sort -rh | head -n 10

# 4. SEGURIDAD Y USUARIO
# Agregamos el usuario y cambiamos el owner de /app para que 'spring' pueda leerlo
RUN addgroup -S spring && adduser -S spring -G spring && \
    chown -R spring:spring /app

USER spring:spring

EXPOSE 8080

# Recomendación: pasar parámetros de memoria para contenedores
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]