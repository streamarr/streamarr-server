package com.streamarr.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the served OpenAPI document to {@code docs/openapi.json}, the copy clients generate types
 * from without a running server. A contract change is committed by refreshing the pin with {@link
 * #REFRESH_COMMAND}, never by hand.
 */
@Tag("IntegrationTest")
@DisplayName("OpenAPI Contract Integration Tests")
class OpenApiContractIT extends AbstractIntegrationTest {

  private static final Path PINNED_DOCUMENT = Path.of("docs/openapi.json");
  private static final String UPDATE_PROPERTY = "openapi.update";

  // Unit tests are skipped and JaCoCo with them: its 100% authorization-coverage check reads the
  // merged execution data, which a single IT cannot satisfy.
  private static final String REFRESH_COMMAND =
      "./mvnw -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true"
          + " -Dit.test=OpenApiContractIT -Dopenapi.update=true verify";

  // Sorted keys, two-space indent, one array element per line: what most editors and
  // JSON.stringify(document, null, 2) produce, so a refresh diffs by the lines that changed.
  private static final JsonMapper CANONICAL_JSON = canonicalJson();

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("Should allow a null rating limit when an invitation has no restriction")
  void shouldAllowNullRatingLimitWhenInvitationHasNoRestriction() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.components.schemas['InvitationLookupResponse'].properties.maximumAllowedRatingAge.type",
                containsInAnyOrder("integer", "null")));
  }

  @ParameterizedTest
  @CsvSource(
      textBlock =
          """
      /api/images/{imageId}, 304
      /api/images/{imageId}, 404
      /api/images/{imageId}, 500
      /api/stream/{sessionId}/multivariant.m3u8, 404
      /api/stream/{sessionId}/stream.m3u8, 404
      /api/stream/{sessionId}/init.mp4, 404
      /api/stream/{sessionId}/init.mp4, 503
      /api/stream/{sessionId}/{segmentName}, 400
      /api/stream/{sessionId}/{segmentName}, 404
      /api/stream/{sessionId}/{segmentName}, 503
      /api/stream/{sessionId}/{variantLabel}/stream.m3u8, 400
      /api/stream/{sessionId}/{variantLabel}/stream.m3u8, 404
      /api/stream/{sessionId}/{variantLabel}/init.mp4, 400
      /api/stream/{sessionId}/{variantLabel}/init.mp4, 404
      /api/stream/{sessionId}/{variantLabel}/init.mp4, 503
      /api/stream/{sessionId}/{variantLabel}/{segmentName}, 400
      /api/stream/{sessionId}/{variantLabel}/{segmentName}, 404
      /api/stream/{sessionId}/{variantLabel}/{segmentName}, 503
      """)
  @DisplayName("Should declare an empty body when a media response has no content")
  void shouldDeclareEmptyBodyWhenMediaResponseHasNoContent(String path, String responseCode)
      throws Exception {
    var responses = servedContract().path("paths").path(path).path("get").path("responses");

    assertThat(responses.has(responseCode)).as("%s response %s", path, responseCode).isTrue();
    assertThat(responses.path(responseCode).has("content")).isFalse();
    assertThat(responses.path("200").has("content")).isTrue();
    assertThat(responses.path("default").path("content").has("application/json")).isTrue();
  }

  @Test
  @DisplayName("Should require the playback query token when stream operations are documented")
  void shouldRequirePlaybackQueryTokenWhenStreamOperationsAreDocumented() throws Exception {
    var operations =
        servedContract()
            .path("paths")
            .propertyStream()
            .filter(path -> path.getKey().startsWith("/api/stream/"))
            .map(path -> path.getValue().path("get"))
            .toList();

    assertThat(operations)
        .isNotEmpty()
        .allSatisfy(
            operation ->
                assertThat(operation.path("parameters").valueStream())
                    .anySatisfy(
                        parameter -> {
                          assertThat(parameter.path("name").asString()).isEqualTo("t");
                          assertThat(parameter.path("in").asString()).isEqualTo("query");
                          assertThat(parameter.path("required").asBoolean()).isTrue();
                          assertThat(parameter.path("schema").path("type").asString())
                              .isEqualTo("string");
                        }));
  }

  @ParameterizedTest
  @CsvSource(
      textBlock =
          """
      /api/images/{imageId}, image/jpeg
      /api/stream/{sessionId}/init.mp4, video/mp4
      /api/stream/{sessionId}/{variantLabel}/init.mp4, video/mp4
      /api/stream/{sessionId}/{segmentName}, video/mp4
      /api/stream/{sessionId}/{segmentName}, video/mp2t
      /api/stream/{sessionId}/{variantLabel}/{segmentName}, video/mp4
      /api/stream/{sessionId}/{variantLabel}/{segmentName}, video/mp2t
      """)
  @DisplayName("Should declare raw binary content when media bytes are served")
  void shouldDeclareRawBinaryContentWhenMediaBytesAreServed(String path, String mediaType)
      throws Exception {
    var content =
        servedContract()
            .path("paths")
            .path(path)
            .path("get")
            .path("responses")
            .path("200")
            .path("content");

    assertThat(content.has(mediaType)).as("%s content type %s", path, mediaType).isTrue();
    assertThat(content.path(mediaType).path("schema").path("type").asString()).isEqualTo("string");
    assertThat(content.path(mediaType).path("schema").path("format").asString())
        .isEqualTo("binary");
    assertThat(content.has("*/*")).isFalse();
  }

  private JsonNode servedContract() throws Exception {
    return CANONICAL_JSON.readTree(
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(UTF_8));
  }

  @Test
  @DisplayName("Should match the pinned document when the OpenAPI contract is served")
  void shouldMatchPinnedDocumentWhenOpenApiContractServed() throws Exception {
    var served =
        canonicalize(
            mockMvc
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(UTF_8));

    if (Boolean.getBoolean(UPDATE_PROPERTY)) {
      Files.writeString(PINNED_DOCUMENT, served, UTF_8);
      return;
    }

    assertThat(PINNED_DOCUMENT)
        .as(
            "%s is behind the served contract; refresh it with: %s",
            PINNED_DOCUMENT, REFRESH_COMMAND)
        .content(UTF_8)
        .isEqualTo(served);
  }

  private static String canonicalize(String json) {
    return CANONICAL_JSON.writeValueAsString(CANONICAL_JSON.readValue(json, Object.class)) + "\n";
  }

  private static JsonMapper canonicalJson() {
    var indenter = new DefaultIndenter("  ", "\n");
    var printer =
        new DefaultPrettyPrinter()
            .withObjectIndenter(indenter)
            .withArrayIndenter(indenter)
            .withSeparators(
                Separators.createDefaultInstance()
                    .withObjectNameValueSpacing(Separators.Spacing.AFTER));
    return JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .defaultPrettyPrinter(printer)
        .build();
  }
}
