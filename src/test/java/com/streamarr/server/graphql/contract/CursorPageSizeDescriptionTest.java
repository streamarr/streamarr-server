package com.streamarr.server.graphql.contract;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.language.Definition;
import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.parser.Parser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Cursor Page Size Description Tests")
class CursorPageSizeDescriptionTest {

  @Test
  @DisplayName("Should document the maximum on every cursor page size when the schema is parsed")
  void shouldDocumentTheMaximumOnEveryCursorPageSizeWhenTheSchemaIsParsed() throws IOException {
    var arguments = new ArrayList<PageSizeArgument>();
    try (var files = Files.list(Path.of("src/main/resources/schema"))) {
      for (var file :
          files.filter(path -> path.toString().endsWith(".graphqls")).sorted().toList()) {
        arguments.addAll(pageSizes(file));
      }
    }

    assertThat(arguments)
        .isNotEmpty()
        .allSatisfy(
            argument -> {
              assertThat(argument.input().getDescription()).as(argument.coordinate()).isNotNull();
              assertThat(argument.input().getDescription().getContent())
                  .as(argument.coordinate())
                  .contains("Maximum 500.");
            });
  }

  private static List<PageSizeArgument> pageSizes(Path file) throws IOException {
    return Parser.parse(Files.readString(file)).getDefinitions().stream()
        .flatMap(definition -> pageSizes(definition).stream())
        .toList();
  }

  private static List<PageSizeArgument> pageSizes(Definition<?> definition) {
    return switch (definition) {
      case ObjectTypeDefinition type -> pageSizes(type.getName(), type.getFieldDefinitions());
      case InterfaceTypeDefinition type -> pageSizes(type.getName(), type.getFieldDefinitions());
      default -> List.of();
    };
  }

  private static List<PageSizeArgument> pageSizes(String type, List<FieldDefinition> fields) {
    return fields.stream()
        .flatMap(
            field ->
                field.getInputValueDefinitions().stream()
                    .filter(
                        input -> input.getName().equals("first") || input.getName().equals("last"))
                    .map(
                        input ->
                            new PageSizeArgument(
                                type + "." + field.getName() + "(" + input.getName() + ")", input)))
        .toList();
  }

  private record PageSizeArgument(String coordinate, InputValueDefinition input) {}
}
