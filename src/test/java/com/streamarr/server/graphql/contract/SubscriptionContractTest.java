package com.streamarr.server.graphql.contract;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.language.AstPrinter;
import graphql.language.Description;
import graphql.language.FieldDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Type;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The subscription schema contract from ADR 0042: every field is nullable so a field error never
 * ends the operation, every field but the ServerAdmin fields and the share field requires a
 * selected Profile with ViewProfileActivity, the share field follows the share queries' reads, the
 * Notifications field follows the Notifications queries' reads, a removed library is identified by
 * id alone, a watch-state change lists its items, each nullable so a failed item leaves the others
 * intact, and every list edge a change carries is an OrderedEdge.
 */
@Tag("UnitTest")
@DisplayName("Subscription Contract Tests")
class SubscriptionContractTest {

  private static final TypeDefinitionRegistry SCHEMA = loadSchema();
  private static final List<String> SERVER_ADMIN_FIELDS =
      List.of("libraryStatusChanged", "activeStreamsChanged");
  private static final String SHARE_FIELD = "profileShareChanged";
  private static final String NOTIFICATIONS_FIELD = "notificationsChanged";

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

  @ParameterizedTest
  @MethodSource("serverAdminFields")
  @DisplayName("Should require a ServerAdmin when a ServerAdmin's subscription field is documented")
  void shouldRequireServerAdminWhenAServerAdminsSubscriptionFieldIsDocumented(String field) {
    assertThat(descriptionOf(subscriptionField(field)))
        .contains("ServerAdmin")
        .doesNotContain("ViewProfileActivity");
  }

  static Stream<String> serverAdminFields() {
    return SERVER_ADMIN_FIELDS.stream();
  }

  @Test
  @DisplayName(
      "Should name the share queries and refuse device-bound sessions when the share field is"
          + " documented")
  void shouldNameTheShareQueriesAndRefuseDeviceBoundSessionsWhenTheShareFieldIsDocumented() {
    assertThat(descriptionOf(subscriptionField(SHARE_FIELD)))
        .contains("householdShares")
        .contains("profileShares")
        .contains("device-bound session")
        .contains("FORBIDDEN")
        .contains("as it delivers it")
        .doesNotContain("ViewProfileActivity");
  }

  @Test
  @DisplayName(
      "Should take the Account from the socket's token and the reads of notifications when the"
          + " Notifications field is documented")
  void shouldTakeTheAccountFromTheSocketsTokenWhenTheNotificationsFieldIsDocumented() {
    assertThat(descriptionOf(subscriptionField(NOTIFICATIONS_FIELD)))
        .contains("access token that opened the socket")
        .contains("The same access as notifications")
        .contains("guarded from the Account's live relationships")
        .doesNotContain("ViewProfileActivity");
  }

  @Test
  @DisplayName(
      "Should deliver a non-null list of nullable Notifications when a Notifications change is"
          + " delivered")
  void shouldDeliverANonNullListOfNullableNotificationsWhenANotificationsChangeIsDelivered() {
    assertThat(objectType("NotificationsChange").getFieldDefinitions())
        .extracting(field -> field.getName() + ": " + AstPrinter.printAst(field.getType()))
        .containsExactly("notifications: [NotificationChange]!", "unreadNotificationCount: Int");
  }

  @Test
  @DisplayName(
      "Should require a selected Profile and ViewProfileActivity when each other subscription field is documented")
  void
      shouldRequireSelectedProfileAndViewProfileActivityWhenEachOtherSubscriptionFieldIsDocumented() {
    assertThat(subscriptionFields())
        .filteredOn(field -> !SERVER_ADMIN_FIELDS.contains(field.getName()))
        .filteredOn(field -> !SHARE_FIELD.equals(field.getName()))
        .filteredOn(field -> !NOTIFICATIONS_FIELD.equals(field.getName()))
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

  @Test
  @DisplayName("Should implement OrderedEdge when a subscription change carries a list's edge")
  void shouldImplementOrderedEdgeWhenASubscriptionChangeCarriesAListsEdge() {
    var edgeTypes =
        subscriptionFields().stream()
            .flatMap(field -> changeFields(typeName(field.getType())))
            .map(field -> typeName(field.getType()))
            .filter(name -> name.endsWith("Edge"))
            .distinct()
            .toList();

    assertThat(edgeTypes).contains("MediaEdge", "ContinueWatchingEdge", "ProfileShareEdge");
    assertThat(edgeTypes)
        .allSatisfy(
            name ->
                assertThat(objectType(name).getImplements())
                    .extracting(SubscriptionContractTest::typeName)
                    .contains("OrderedEdge"));
  }

  // The fields of a change, and of the changes it lists, such as a watch-state change's Continue
  // Watching changes.
  private static Stream<FieldDefinition> changeFields(String typeName) {
    return SCHEMA.getType(typeName, ObjectTypeDefinition.class).stream()
        .flatMap(type -> type.getFieldDefinitions().stream())
        .flatMap(
            field ->
                typeName(field.getType()).endsWith("Change")
                    ? Stream.concat(Stream.of(field), changeFields(typeName(field.getType())))
                    : Stream.of(field));
  }

  private static String typeName(Type<?> type) {
    return switch (type) {
      case NonNullType nonNull -> typeName(nonNull.getType());
      case ListType list -> typeName(list.getType());
      case TypeName name -> name.getName();
      default -> throw new IllegalArgumentException("unexpected type " + type);
    };
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
