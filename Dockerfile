FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml ./
RUN mvn -B dependency:go-offline

RUN mvn -B dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/build/target/dependency

COPY src ./src
RUN mvn -B -DskipTests package


FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /build/target/dependency/*.jar ./playwright-deps/
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && java -cp "playwright-deps/*" com.microsoft.playwright.CLI install --with-deps chromium \
    && rm -rf playwright-deps /var/lib/apt/lists/*

ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

COPY --from=build /build/target/*.jar ./staging/
RUN find ./staging -maxdepth 1 -name '*.jar' ! -name '*.jar.original' -exec cp {} app.jar \; \
    && rm -rf staging

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
