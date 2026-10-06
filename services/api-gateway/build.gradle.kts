plugins {
    java
    // Boot 4.0.x, not 4.1: the latest stable Spring Cloud train (2025.1) supports only 4.0 (ADR 0006).
    id("org.springframework.boot") version "4.0.8"
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

extra["springCloudVersion"] = "2025.1.3"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Reactive gateway (Netty): few threads, so it stays small in memory while proxying many connections.
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    // Validates access tokens against identity-service's JWKS.
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    // Rate-limit counters (token buckets) shared by all gateway replicas.
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-webtestclient")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("io.projectreactor:reactor-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

// Only the executable Spring Boot jar; the plain jar is never used.
tasks.jar {
    enabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "384m"
}
