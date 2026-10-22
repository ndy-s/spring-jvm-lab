# Stage 1: build the jar
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Copy only the pom first so Docker can cache the dependency download layer.
# This layer only invalidates when pom.xml changes, not on every source edit.
COPY pom.xml .
RUN mvn dependency:go-offline

COPY src ./src
RUN mvn package -DskipTests

# Stage 2: run the jar on a minimal JRE (no Maven, no JDK compiler, no source tree)
FROM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=build /build/target/*.jar app.jar

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
