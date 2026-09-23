FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml ./
RUN mvn -B dependency:go-offline
# Resolved from pom.xml only, so this layer (and the Chromium install that
# uses it) stays cached across source-code changes.
RUN mvn -B dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/build/target/dependency

COPY src ./src
RUN mvn -B -DskipTests package


FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Playwright drives a real Chromium browser, so the runtime image needs the
# browser binary plus its OS-level shared libraries installed. Kept as its
# own layer, ahead of the app jar, so code changes don't force a re-download.
COPY --from=build /build/target/dependency/*.jar ./playwright-deps/
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && java -cp "playwright-deps/*" com.microsoft.playwright.CLI install --with-deps chromium \
    && rm -rf playwright-deps /var/lib/apt/lists/*

# spring-boot-maven-plugin repackages target/*.jar in place and keeps the
# pre-repackage jar as *.jar.original, so pick the executable one explicitly.
COPY --from=build /build/target/*.jar ./staging/
RUN find ./staging -maxdepth 1 -name '*.jar' ! -name '*.jar.original' -exec cp {} app.jar \; \
    && rm -rf staging

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
