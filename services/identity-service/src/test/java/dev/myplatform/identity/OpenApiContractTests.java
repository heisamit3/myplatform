package dev.myplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * contracts/openapi/identity.yaml is generated from the code (ADR 0004). This test fails when the
 * committed file no longer matches, so an API change can't slip in without a visible contract diff.
 * Regenerate with: UPDATE_CONTRACTS=true ./gradlew test --tests '*OpenApiContractTests'
 */
@WebIntegrationTest
class OpenApiContractTests {

    // Gradle runs tests from the service directory.
    private static final Path CONTRACT = Path.of("../../contracts/openapi/identity.yaml");
    private static final String HEADER = """
            # GENERATED from identity-service code by OpenApiContractTests. Do not edit by hand.
            # Regenerate: cd services/identity-service && UPDATE_CONTRACTS=true ./gradlew test --tests '*OpenApiContractTests'
            """;

    @Autowired
    RestTestClient http;

    @Test
    void committedContractMatchesTheCode() throws IOException {
        String spec = http.get().uri("/v3/api-docs.yaml").exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseBody();
        String expected = HEADER + spec.replace("\r\n", "\n").stripTrailing() + "\n";

        if ("true".equals(System.getenv("UPDATE_CONTRACTS"))) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, expected);
            return;
        }
        assertThat(CONTRACT).as("missing contract, generate it (see class comment)").exists();
        assertThat(Files.readString(CONTRACT).replace("\r\n", "\n"))
                .as("contracts/openapi/identity.yaml is out of date; regenerate it (see class comment)")
                .isEqualTo(expected);
    }

}
