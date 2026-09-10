package com.streamarr.server.graphql.contract;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Credential Journal Boundary Tests")
class CredentialJournalBoundaryTest {
  @Test
  @DisplayName("Should keep journal records internal when HTTP and GraphQL adapters are inspected")
  void shouldKeepJournalRecordsInternalWhenHttpAndGraphQlAdaptersAreInspected() {
    var adapters =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.streamarr.server.controllers", "com.streamarr.server.graphql");
    noClasses()
        .should()
        .dependOnClassesThat()
        .haveNameMatching("com\\.streamarr\\.server\\.domain\\.auth\\.CredentialAttempt.*")
        .check(adapters);
  }

  @Test
  @DisplayName("Should expose no journal surface when the GraphQL schema is loaded")
  void shouldExposeNoJournalSurfaceWhenGraphQlSchemaIsLoaded() throws Exception {
    try (var files = Files.list(Path.of("src/main/resources/schema"))) {
      var schemas = files.filter(file -> file.toString().endsWith(".graphqls")).toList();
      assertThat(schemas).isNotEmpty();
      for (var file : schemas) {
        var registry = new SchemaParser().parse(Files.readString(file));
        assertThat(registry.types().keySet())
            .noneMatch(name -> name.startsWith("CredentialAttempt"));
        assertThat(
                Stream.concat(
                        registry.getTypes(ObjectTypeDefinition.class).stream(),
                        registry.objectTypeExtensions().values().stream().flatMap(List::stream))
                    .flatMap(type -> type.getFieldDefinitions().stream())
                    .map(field -> field.getName())
                    .toList())
            .noneMatch(name -> name.startsWith("credentialAttempt"));
      }
    }
  }
}
