# VINSight API - imagem de producao (Sprint 3, Cybersecurity: IaC Security)
#
# Multi-stage: o JDK, o Maven e o codigo-fonte ficam na etapa de build e NAO vao para a imagem final.
# A imagem final tem so o JRE e o .jar: menos pacotes = menos CVEs para o Trivy achar.
# Segredos (DB_*, JWT_SECRET) entram por variavel de ambiente na hora do "docker run", nunca aqui.
#
#   docker build -t vinsight-api .
#   docker run -p 8080:8080 -e DB_URL=... -e DB_USER=... -e DB_PASSWORD=... -e JWT_SECRET=... \
#              --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges vinsight-api

# ---------- Etapa 1: build ----------
FROM eclipse-temurin:24-jdk-alpine AS build
WORKDIR /build

COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
COPY src/ src/
# Os testes rodam no job de CI (com MySQL); aqui so empacota
RUN chmod +x mvnw \
    && ./mvnw -B -q package -DskipTests -Djacoco.skip=true \
    && cp target/vinsight-api-*.jar app.jar

# ---------- Etapa 2: runtime ----------
FROM eclipse-temurin:24-jre-alpine

# Usuario sem privilegio, sem shell de login e sem home
RUN addgroup -S vinsight && adduser -S -G vinsight -H -s /sbin/nologin vinsight

WORKDIR /app
# O .jar pertence ao root e o processo roda como "vinsight": a aplicacao nao consegue se alterar
COPY --from=build /build/app.jar app.jar

USER vinsight

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=America/Sao_Paulo"

EXPOSE 8080

# Orquestrador reinicia o container se a API parar de responder
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
