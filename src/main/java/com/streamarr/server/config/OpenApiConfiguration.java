package com.streamarr.server.config;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import com.streamarr.server.config.security.AuthCookies;
import com.streamarr.server.config.security.StreamarrBearerTokenResolver;
import com.streamarr.server.controllers.auth.AuthErrorResponse;
import com.streamarr.server.services.auth.TokenScope;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
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
  private static final String BEARER_AUTH = "bearerAuth";
  private static final String ACCESS_COOKIE = "accessCookie";
  private static final String PLAYBACK_QUERY = "playbackQuery";

  /** A fixed server entry keeps the random port a test server binds out of the document. */
  @Bean
  OpenAPI streamarrOpenApi() {
    return new OpenAPI()
        .info(new Info().title("Streamarr").version("v1"))
        .servers(List.of(new Server().url("/")))
        .schemaRequirement(
            BEARER_AUTH,
            new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description(
                    "JWT access token. Profile tokens also satisfy SCOPE_ACCOUNT requirements."))
        .schemaRequirement(
            ACCESS_COOKIE,
            new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name(AuthCookies.ACCESS_COOKIE)
                .description(
                    "JWT access token. Profile tokens also satisfy SCOPE_ACCOUNT requirements."
                        + " Unsafe cookie-authenticated requests also require the X-XSRF-TOKEN"
                        + " header."))
        .schemaRequirement(
            PLAYBACK_QUERY,
            new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.QUERY)
                .name("t")
                .description(
                    "Playback token bound to this stream session. Stream endpoints ignore bearer"
                        + " headers and access cookies."));
  }

  @Bean
  OpenApiCustomizer securityRequirementCustomizer() {
    return openApi ->
        openApi
            .getPaths()
            .forEach(
                (path, item) ->
                    item.readOperations()
                        .forEach(operation -> operation.setSecurity(securityRequirements(path))));
  }

  private static List<SecurityRequirement> securityRequirements(String path) {
    if (StreamarrBearerTokenResolver.UNAUTHENTICATED_PATHS.contains(path)) {
      return List.of();
    }

    if (path.startsWith("/api/stream/")) {
      return List.of(
          new SecurityRequirement().addList(PLAYBACK_QUERY, TokenScope.PLAYBACK.authority()));
    }

    var scope = path.startsWith("/api/images/") ? TokenScope.PROFILE : TokenScope.ACCOUNT;
    return List.of(
        new SecurityRequirement().addList(BEARER_AUTH, scope.authority()),
        new SecurityRequirement().addList(ACCESS_COOKIE, scope.authority()));
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
