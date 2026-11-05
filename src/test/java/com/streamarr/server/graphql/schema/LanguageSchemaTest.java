package com.streamarr.server.graphql.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.language.Language;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Generates the GraphQL {@code Language} enum from the server's Language table, so the two never
 * drift. A table change is committed by refreshing the schema file with {@link #REFRESH_COMMAND},
 * never by hand.
 */
@Tag("UnitTest")
@DisplayName("Language Schema Tests")
class LanguageSchemaTest {

  private static final Path SCHEMA_FILE = Path.of("src/main/resources/schema/language.graphqls");
  private static final String UPDATE_PROPERTY = "language.schema.update";
  private static final String REFRESH_COMMAND =
      "./mvnw test -Dtest=LanguageSchemaTest -Djacoco.skip=true -D" + UPDATE_PROPERTY + "=true";

  @Test
  @DisplayName("Should list every value of the Language table when the schema file is read")
  void shouldListEveryValueOfTheLanguageTableWhenTheSchemaFileIsRead() throws IOException {
    var generated = generatedSchema();
    if (Boolean.getBoolean(UPDATE_PROPERTY)) {
      Files.writeString(SCHEMA_FILE, generated);
    }

    assertThat(SCHEMA_FILE).exists();
    assertThat(Files.readString(SCHEMA_FILE))
        .as("%s drifted from the Language table; refresh it with: %s", SCHEMA_FILE, REFRESH_COMMAND)
        .isEqualTo(generated);
  }

  private static String generatedSchema() {
    var values =
        Arrays.stream(Language.values())
            .map(language -> "    \"%s\"%n    %s%n".formatted(language.tag(), language.name()))
            .collect(Collectors.joining());
    return """
        # Generated from the server's Language table by LanguageSchemaTest. Do not edit by hand.
        # Refresh: %s

        \"""
        A language that a Profile's language preferences name (ADR 0043). Each value's description is
        its BCP 47 tag. The server maps each value to the codes that files tag a stream with, such as
        fr, fra and fre for FR. A tag with a region or script that has no value of its own, such as
        pt-PT, reads as its base language.
        \"""
        enum Language {
        %s}
        """
        .formatted(REFRESH_COMMAND, values);
  }
}
