FROM eclipse-temurin:21-jdk AS build
ARG SERVICE
WORKDIR /src
COPY . .
RUN ./mvnw -B -q -pl ${SERVICE} -am package -DskipTests && cp ${SERVICE}/target/${SERVICE}-*-exec.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
