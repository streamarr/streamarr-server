package com.streamarr.server.graphql.contract;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.language.AstPrinter;
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
 * The subscription schema contract from ADR 0042: every field requires a selected Profile with
 * ViewProfileActivity and is nullable so a field error never ends the operation, a removed library
 * is identified by id alone, and a watch-state change lists its items, each nullable so a failed
 * item leaves the others intact.
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
        .contains("PROFILE_REQUIRED")
        .contains("SUBSCRIPTION_OVERFLOW");
  }

  @Test
  @DisplayName("Should check extension fields when enforcing the subscription contract")
  void shouldCheckExtensionFieldsWhenEnforcingSubscriptionContract() {
    assertThat(subscriptionFields())
        .extracting(FieldDefinition::getName)
        .contains(
            "libraryItemAdded", "libraryItemUpdated", "libraryItemRemoved", "libraryCountsChanged");
  }

  @Test
  @DisplayName("Should require a ServerAdmin when the library status field is documented")
  void shouldRequireServerAdminWhenLibraryStatusFieldIsDocumented() {
    assertThat(descriptionOf(subscriptionField("libraryStatusChanged")))
        .contains("ServerAdmin")
        .doesNotContain("ViewProfileActivity");
  }

  @Test
  @DisplayName(
      "Should require a selected Profile and ViewProfileActivity when each other subscription field is documented")
  void
      shouldRequireSelectedProfileAndViewProfileActivityWhenEachOtherSubscriptionFieldIsDocumented() {
    assertThat(subscriptionFields())
        .filteredOn(field -> !field.getName().equals("libraryStatusChanged"))
        .isNotEmpty()
        .allSatisfy(
            field ->
                assertThat(descriptionOf(field))
                    .contains("selected Profile")
                    .contains("ViewProfileActivity")
                    .doesNotContain("ServerAdmin"));
  }

  @Test
  @DisplayName(
      "Should name the selected Profile and its permission when the watch-state field is documented")
  void shouldNameSelectedProfileAndItsPermissionWhenWatchStateFieldIsDocumented() {
    assertThat(descriptionOf(subscriptionField("watchStateChanged")))
        .contains("selected Profile")
        .contains("ViewProfileActivity")
        .contains("continueWatching")
        .contains("edge");
  }

  @Test
  @DisplayName(
      "Should deliver a non-null list of nullable items when a watch-state change is delivered")
  void shouldDeliverNonNullListOfNullableItemsWhenWatchStateChangeIsDelivered() {
    assertThat(objectType("WatchStateChange").getFieldDefinitions())
        .extracting(field -> field.getName() + ": " + AstPrinter.printAst(field.getType()))
        .containsExactly("items: [WatchStateItem]!", "continueWatching: [ContinueWatchingChange]");
  }

  @Test
  @DisplayName("Should declare a nullable type when any subscription field is defined")
  void shouldDeclareNullableTypeWhenAnySubscriptionFieldIsDefined() {
    assertThat(subscriptionFields())
        .isNotEmpty()
        .allSatisfy(field -> assertThat(field.getType()).isNotInstanceOf(NonNullType.class));
  }

  @Test
  @DisplayName("Should identify the removed library by its id alone")
  void shouldIdentifyRemovedLibraryByItsIdAlone() {
    var removedLibrary = objectType("RemovedLibrary");

    assertThat(removedLibrary.getFieldDefinitions())
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

  @Test
  @DisplayName("Should expose Continue Watching membership on edges instead of media nodes")
  void shouldExposeContinueWatchingMembershipOnEdgesInsteadOfMediaNodes() {
    for (var type : List.of("Movie", "Episode")) {
      assertThat(objectType(type).getFieldDefinitions())
          .extracting(FieldDefinition::getName)
          .doesNotContain("continueWatchingPosition");
    }

    assertThat(SCHEMA.getType("ContinueWatchingPosition")).isEmpty();
    assertThat(objectType("ContinueWatchingChange").getFieldDefinitions())
        .filteredOn(field -> field.getName().equals("edge"))
        .singleElement()
        .satisfies(
            field ->
                assertThat(AstPrinter.printAst(field.getType())).isEqualTo("ContinueWatchingEdge"));
  }

  private static List<FieldDefinition> subscriptionFields() {
    return Stream.concat(
            objectType("Subscription").getFieldDefinitions().stream(),
            SCHEMA.objectTypeExtensions().getOrDefault("Subscription", List.of()).stream()
                .flatMap(extension -> extension.getFieldDefinitions().stream()))
        .toList();
  }

  private static FieldDefinition subscriptionField(String name) {
    return subscriptionFields().stream()
        .filter(field -> field.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError(name + " is not a subscription field"));
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
