package com.streamarr.server.graphql.contract;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.idl.SchemaGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR 0063 specifies kinds that no build makes yet. Their types are written here, in test SDL
 * alone, over the live schema, to show that the Notification interfaces take them as they stand: a
 * library's news validates beside the kinds the server sends, and the list and its live changes
 * carry any Notification without a schema change.
 */
@Tag("UnitTest")
@DisplayName("Specified-Only Kinds Schema Fit Tests")
class SpecifiedOnlyKindsSchemaFitTest {

  private static final String SPECIFIED_ONLY_KINDS =
      """
      "Test SDL only: a scan or refresh of a library finished."
      type LibraryScanFinishedNotification implements Notification {
          id: ID!
          message: String!
          createdAt: String!
          latestEventAt: String!
          unread: Boolean!
          libraryId: ID!
          libraryName: String!
          added: Int!
          removed: Int!
          failed: Int!
      }
      """;

  @Test
  @DisplayName(
      "Should validate a library's news beside the kinds the server sends when it implements the"
          + " Notification interface")
  void shouldValidateALibrarysNewsBesideTheKindsTheServerSendsWhenItImplementsTheInterface()
      throws IOException {
    var schema = SchemaGenerator.createdMockedSchema(liveSchema() + "\n" + SPECIFIED_ONLY_KINDS);

    assertThat(schema.getImplementations(interfaceOf(schema, "Notification")))
        .extracting(GraphQLObjectType::getName)
        .contains("LibraryScanFinishedNotification", "ProfileShareEndedNotification");
    assertThat(schema.getImplementations(interfaceOf(schema, "RequestNotification")))
        .extracting(GraphQLObjectType::getName)
        .containsExactlyInAnyOrder(
            "ProfileShareOfferedNotification", "ProfileManagerInvitationReceivedNotification");
  }

  @Test
  @DisplayName(
      "Should carry any Notification in the list's edges when a kind is added beside the kinds the"
          + " server sends")
  void shouldCarryAnyNotificationInTheListsEdgesWhenAKindIsAddedBesideTheKindsTheServerSends()
      throws IOException {
    var schema = SchemaGenerator.createdMockedSchema(liveSchema() + "\n" + SPECIFIED_ONLY_KINDS);

    var edge = (GraphQLObjectType) schema.getType("NotificationEdge");

    assertThat(
            GraphQLTypeUtil.simplePrint(
                GraphQLTypeUtil.unwrapAll(edge.getFieldDefinition("node").getType())))
        .isEqualTo("Notification");
  }

  private static GraphQLInterfaceType interfaceOf(GraphQLSchema schema, String name) {
    return (GraphQLInterfaceType) schema.getType(name);
  }

  private static String liveSchema() throws IOException {
    try (var files = Files.list(Path.of("src/main/resources/schema"))) {
      return files
          .filter(path -> path.toString().endsWith(".graphqls"))
          .sorted()
          .map(SpecifiedOnlyKindsSchemaFitTest::read)
          .collect(Collectors.joining("\n"));
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new IllegalStateException("schema file is unreadable: " + file, e);
    }
  }
}
