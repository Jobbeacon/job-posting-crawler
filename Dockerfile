FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml ./
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package


FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /build/target/*.jar ./staging/
RUN find ./staging -maxdepth 1 -name '*.jar' ! -name '*.jar.original' -exec cp {} app.jar \; \
    && rm -rf staging

RUN java -Djarmode=tools -jar app.jar extract --destination extracted \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && java -cp "extracted/lib/*" com.microsoft.playwright.CLI install --with-deps chromium \
    && rm -rf extracted /var/lib/apt/lists/*

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
