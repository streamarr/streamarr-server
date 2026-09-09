package com.streamarr.server.config;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import com.streamarr.server.controllers.auth.AuthErrorResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The REST contract as OpenAPI, served at {@code /v3/api-docs} wherever {@code
 * springdoc.api-docs.enabled} is true (the dev and test profiles). {@code docs/openapi.json} is the
 * pinned copy clients generate types from; {@code OpenApiContractIT} keeps the two identical.
 */
@Configuration
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfiguration {

  private static final String REFUSAL_SCHEMA = "AuthErrorResponse";
  private static final String REFUSAL_SCHEMA_REF = "#/components/schemas/" + REFUSAL_SCHEMA;

  /** A fixed server entry keeps the random port a test server binds out of the document. */
  @Bean
  OpenAPI streamarrOpenApi() {
    return new OpenAPI()
        .info(new Info().title("Streamarr").version("v1"))
        .servers(List.of(new Server().url("/")));
  }

  /**
   * JSON refusals share the {@code {code, message}} body. Media controllers declare their bodyless
   * response statuses explicitly, overriding this default for those outcomes.
   */
  @Bean
  OpenApiCustomizer refusalResponseCustomizer() {
    return openApi -> {
      openApi.getComponents().addSchemas(REFUSAL_SCHEMA, refusalSchema());
      openApi.getPaths().values().stream()
          .map(PathItem::readOperations)
          .flatMap(List::stream)
          .forEach(operation -> operation.getResponses().addApiResponse("default", refusal()));
    };
  }

  private static Schema<?> refusalSchema() {
    return ModelConverters.getInstance(true)
        .resolveAsResolvedSchema(new AnnotatedType(AuthErrorResponse.class).resolveAsRef(false))
        .schema;
  }

  private static ApiResponse refusal() {
    return new ApiResponse()
        .description("Refusal: route on `code`, `message` is displayable")
        .content(
            new Content()
                .addMediaType(
                    APPLICATION_JSON_VALUE,
                    new MediaType().schema(new Schema<>().$ref(REFUSAL_SCHEMA_REF))));
  }
}
