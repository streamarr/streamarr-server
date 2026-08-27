package com.streamarr.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
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
