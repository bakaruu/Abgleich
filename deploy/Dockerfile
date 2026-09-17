# Container image of the application. The jar is built beforehand by `./gradlew :bootstrap:bootJar`, in CI only
# after every test passed, so the image build needs neither Gradle nor Docker-in-Docker for Testcontainers.
#
#   ./gradlew :bootstrap:bootJar
#   docker build -f deploy/Dockerfile -t abgleich:local .

FROM eclipse-temurin:21.0.12_8-jre-alpine AS layers
WORKDIR /build
COPY bootstrap/build/libs/abgleich.jar application.jar
# Dependencies change less often than the code: separate layers keep image updates small.
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21.0.12_8-jre-alpine
RUN addgroup -S abgleich && adduser -S -G abgleich abgleich
WORKDIR /application
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
USER abgleich
EXPOSE 8080 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
