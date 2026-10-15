package com.streamarr.server.graphql.contract;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.language.Description;
import graphql.language.FieldDefinition;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The subscription schema contract from ADR 0042: every field is ServerAdmin only and documented,
 * delivers a non-null value, and identifies a removed library by id alone.
 */
@Tag("UnitTest")
@DisplayName("Subscription Contract Tests")
class SubscriptionContractTest {

  private static final TypeDefinitionRegistry SCHEMA = loadSchema();

  @Test
  @DisplayName("Should name the transport and recovery when the Subscription type is documented")
  void shouldNameTransportAndRecoveryWhenSubscriptionTypeIsDocumented() {
    assertThat(descriptionOf(objectType("Subscription")))
        .contains("graphql-transport-ws")
        .contains("libraries")
        .contains("SUBSCRIPTION_OVERFLOW");
  }

  @Test
  @DisplayName(
      "Should state the ServerAdmin requirement when each subscription field is documented")
  void shouldStateServerAdminRequirementWhenEachSubscriptionFieldIsDocumented() {
    assertThat(subscriptionFields())
        .isNotEmpty()
        .allSatisfy(field -> assertThat(descriptionOf(field)).contains("ServerAdmin"));
  }

  @Test
  @DisplayName("Should deliver a non-null value when any subscription field emits")
  void shouldDeliverNonNullValueWhenAnySubscriptionFieldEmits() {
    assertThat(subscriptionFields())
        .allSatisfy(field -> assertThat(field.getType()).isInstanceOf(NonNullType.class));
  }

  @Test
  @DisplayName("Should identify the removed library by id alone when a removal is delivered")
  void shouldIdentifyRemovedLibraryByIdAloneWhenRemovalIsDelivered() {
    var removal = objectType("LibraryRemoval");

    assertThat(removal.getFieldDefinitions())
        .singleElement()
        .satisfies(
            field -> {
              assertThat(field.getName()).isEqualTo("libraryId");
              assertThat(field.getType())
                  .isInstanceOfSatisfying(
                      NonNullType.class,
                      nonNull ->
                          assertThat(((TypeName) nonNull.getType()).getName()).isEqualTo("ID"));
            });
  }

  private static List<FieldDefinition> subscriptionFields() {
    return objectType("Subscription").getFieldDefinitions();
  }

  private static ObjectTypeDefinition objectType(String name) {
    return SCHEMA
        .getType(name, ObjectTypeDefinition.class)
        .orElseThrow(() -> new AssertionError(name + " is not in the schema"));
  }

  private static String descriptionOf(ObjectTypeDefinition type) {
    return textOf(type.getDescription());
  }

  private static String descriptionOf(FieldDefinition field) {
    return textOf(field.getDescription());
  }

  private static String textOf(Description description) {
    assertThat(description).as("a description").isNotNull();
    return description.getContent();
  }

  private static TypeDefinitionRegistry loadSchema() {
    var registry = new TypeDefinitionRegistry();
    var parser = new SchemaParser();
    try (Stream<Path> files = Files.list(Path.of("src/main/resources/schema"))) {
      files
          .filter(file -> file.toString().endsWith(".graphqls"))
          .sorted()
          .forEach(file -> registry.merge(parser.parse(read(file))));
    } catch (IOException e) {
      throw new IllegalStateException("schema directory is unreadable", e);
    }

    return registry;
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new IllegalStateException("schema file is unreadable: " + file, e);
    }
  }
}
