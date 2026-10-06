package dev.myplatform.identity;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Code-first OpenAPI (ADR 0004). The spec is served at /v3/api-docs(.yaml) and exported to
 * contracts/openapi/identity.yaml by OpenApiContractTests.
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(title = "identity-service", version = "v1",
                description = "Users, organizations (tenants), memberships, login and tokens."),
        servers = @Server(url = "http://localhost:8081", description = "Local, direct. Clients use the gateway."))
@SecurityScheme(name = OpenApiConfiguration.BEARER, type = SecuritySchemeType.HTTP, scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfiguration {

    /** Name of the security scheme; put {@code @SecurityRequirement(name = BEARER)} on protected controllers. */
    public static final String BEARER = "bearerAuth";

    private static final String PROBLEM_SCHEMA = "ProblemDetail";

    /**
     * Every error is RFC 9457 problem+json. Controllers only list status codes and descriptions; this sets
     * the body of every 4xx/5xx response (springdoc would otherwise copy the success schema into them).
     * Tags are sorted so the exported contract is byte-for-byte stable.
     */
    @Bean
    OpenApiCustomizer problemDetailErrors() {
        return openApi -> {
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, new ObjectSchema()
                    .description("RFC 9457 problem details")
                    .properties(Map.of(
                            "type", new StringSchema().format("uri-reference").example("about:blank"),
                            "title", new StringSchema().example("Conflict"),
                            "status", new IntegerSchema().example(409),
                            "detail", new StringSchema().example("Email is already registered"),
                            "instance", new StringSchema().format("uri-reference").example("/auth/register")))
                    .required(List.of("type", "title", "status")));
            Content problem = new Content().addMediaType("application/problem+json",
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA)));
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation ->
                    operation.getResponses().forEach((status, response) -> {
                        if (status.matches("[45]\\d\\d")) {
                            response.setContent(problem);
                        }
                    })));
            if (openApi.getTags() != null) {
                openApi.getTags().sort(Comparator.comparing(Tag::getName));
            }
        };
    }

}
