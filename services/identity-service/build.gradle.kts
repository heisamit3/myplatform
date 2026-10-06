plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.myplatform"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Bearer-token auth for /me and /orgs; also brings Nimbus + Spring JwtEncoder for signing.
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    // bcrypt password hashing.
    implementation("org.springframework.security:spring-security-crypto")
    // OpenAPI spec generated from the controllers; exported to contracts/openapi/ by a test.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.1")
    // Domain events to Kafka (contracts/events/).
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    // Validates published events against contracts/events/*.schema.json.
    testImplementation("com.networknt:json-schema-validator:3.0.8")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Only the executable Spring Boot jar; the plain jar is never used.
tasks.jar {
    enabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "384m"
    // OpenApiContractTests compares against this folder, so editing it must re-run the tests.
    inputs.files("../../contracts/openapi").withPropertyName("contracts")
    inputs.files("../../contracts/events").withPropertyName("eventContracts")
    inputs.property("updateContracts", System.getenv("UPDATE_CONTRACTS") ?: "")
}
