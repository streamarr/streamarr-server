package com.streamarr.server.graphql.contract;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR 0026's mutation shape, enforced structurally: one required {@code input} argument typed
 * {@code *Input}, a nullable mutation-specific {@code *Payload} whose {@code userErrors} is a
 * non-null list of a non-null union, every union member implementing {@code MutationError}, and
 * nothing returned bare. The legacy mutations below predate the ADR and are allowed by name until
 * each migrates; the list can only shrink.
 */
@Tag("UnitTest")
@DisplayName("Mutation Contract Tests")
class MutationContractTest {

  /** Migrated one domain slice at a time; remove a name here when its mutation migrates. */
  private static final Set<String> LEGACY_MUTATIONS =
      Set.of(
          "addRating",
          "removeLibrary",
          "scanLibrary",
          "refreshLibrary",
          "createStreamSession",
          "destroyStreamSession",
          "reportStreamSessionTimeline",
          "markWatched",
          "markUnwatched");

  private static final Set<String> INPUT_ERROR_UNIONS = Set.of("AddLibraryError");

  private static final TypeDefinitionRegistry SCHEMA = loadSchema();

  @Test
  @DisplayName("Should describe when a Household must retain an administrator")
  void shouldDescribeWhenHouseholdMustRetainAdministrator() {
    assertThat(descriptionOf("LastHouseholdAdminError"))
        .isEqualTo(
            "After its first Account, a Household must keep at least one HouseholdAdmin until the Household is deleted.");
  }

  @Test
  @DisplayName("Should describe final Account removal as Household deletion")
  void shouldDescribeFinalAccountRemovalAsHouseholdDeletion() {
    assertThat(descriptionOf("LastHouseholdAccountError"))
        .isEqualTo("The last Account can be removed only by deleting the Household.");
  }

  @Test
  @DisplayName("Should describe linked Profile handling in two sentences")
  void shouldDescribeLinkedProfileHandlingInTwoSentences() {
    assertThat(descriptionOf("ProfileBelongsToAccountError"))
        .isEqualTo("The Profile belongs to an Account. Transfer or delete the Account instead.");
  }

  @Test
  @DisplayName(
      "Should model Account invitation Profile choices as distinct GraphQL actions when the schema is parsed")
  void shouldModelAccountInvitationProfileChoicesAsDistinctGraphqlActionsWhenSchemaIsParsed() {
    assertThat(SCHEMA.getType("AccountInvitationMode")).isEmpty();
    assertThat(SCHEMA.getType("LinkProfileRequiredError")).isEmpty();
    assertThat(SCHEMA.getType("ProfileNotInHouseholdError")).isEmpty();
    assertThat(mutationFields().stream().map(FieldDefinition::getName))
        .contains(
            "issueAccountInvitationWithNewProfile", "issueAccountInvitationForExistingProfile")
        .doesNotContain("issueAccountInvitation");

    var details =
        SCHEMA.getType("AccountInvitationDetails", ObjectTypeDefinition.class).orElseThrow();
    assertThat(fieldNames(details.getFieldDefinitions()))
        .contains("profile")
        .doesNotContain(
            "mode", "profileId", "profileName", "profileKind", "maximumAllowedRatingAge");
  }

  @Test
  @DisplayName("Should report no shape violations when non-legacy mutations conform to ADR 0026")
  void shouldReportNoShapeViolationsWhenNonLegacyMutationsConformToAdr0026() {
    var violations = new ArrayList<String>();
    for (var mutation : mutationFields()) {
      if (LEGACY_MUTATIONS.contains(mutation.getName())) {
        continue;
      }

      violations.addAll(shapeViolations(mutation));
    }

    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("Should require allowlist entry removal when a legacy mutation conforms")
  void shouldRequireAllowlistEntryRemovalWhenLegacyMutationConforms() {
    var declared = mutationFields().stream().map(FieldDefinition::getName).toList();

    assertThat(declared).as("allowlisted names must still exist").containsAll(LEGACY_MUTATIONS);
    for (var mutation : mutationFields()) {
      if (LEGACY_MUTATIONS.contains(mutation.getName())) {
        assertThat(shapeViolations(mutation))
            .as(
                "%s is allowlisted but already conforms; remove it from the list",
                mutation.getName())
            .isNotEmpty();
      }
    }
  }

  @Test
  @DisplayName("Should require shared interfaces when mutation error types are declared")
  void shouldRequireSharedInterfacesWhenMutationErrorTypesAreDeclared() {
    assertThat(errorInterfaceViolations(SCHEMA)).isEmpty();
  }

  @Test
  @DisplayName("Should require unique member types when mutation error unions are declared")
  void shouldRequireUniqueMemberTypesWhenMutationErrorUnionsAreDeclared() {
    var duplicateMemberUnions =
        errorUnions(SCHEMA).stream()
            .filter(
                union ->
                    union.getMemberTypes().stream()
                            .map(type -> ((TypeName) type).getName())
                            .distinct()
                            .count()
                        != union.getMemberTypes().size())
            .map(UnionTypeDefinition::getName)
            .toList();

    assertThat(duplicateMemberUnions).isEmpty();
  }

  @Test
  @DisplayName("Should require the mutation-specific error union when payload declares user errors")
  void shouldRequireMutationSpecificErrorUnionWhenPayloadDeclaresUserErrors() {
    var fixture =
        mutationFixture(
            "example(input: ExampleInput!): ExamplePayload",
            """
            type ExamplePayload {
              result: String
              userErrors: [OtherMutationError!]!
            }

            union OtherMutationError = ExampleInputError
            """);

    assertThat(shapeViolations(fixture, fixtureMutation(fixture)))
        .containsExactly("example: payload must declare userErrors: [ExampleError!]!");
  }

  @Test
  @DisplayName("Should report an input violation when a mutation input is not required")
  void shouldReportInputViolationWhenMutationInputIsNotRequired() {
    var fixture =
        mutationFixture(
            "example(input: ExampleInput): ExamplePayload", conformingPayloadDefinition());

    assertThat(shapeViolations(fixture, fixtureMutation(fixture)))
        .containsExactly("example: expects exactly one argument input: ExampleInput!");
  }

  @Test
  @DisplayName("Should report an input violation when a mutation declares an extra argument")
  void shouldReportInputViolationWhenMutationDeclaresExtraArgument() {
    var fixture =
        mutationFixture(
            "example(input: ExampleInput!, extra: String): ExamplePayload",
            conformingPayloadDefinition());

    assertThat(shapeViolations(fixture, fixtureMutation(fixture)))
        .containsExactly("example: expects exactly one argument input: ExampleInput!");
  }

  @Test
  @DisplayName("Should report a payload violation when a mutation payload is non-null")
  void shouldReportPayloadViolationWhenMutationPayloadIsNonNull() {
    var fixture =
        mutationFixture(
            "example(input: ExampleInput!): ExamplePayload!", conformingPayloadDefinition());

    assertThat(shapeViolations(fixture, fixtureMutation(fixture)))
        .containsExactly("example: expects a nullable ExamplePayload return type");
  }

  @Test
  @DisplayName("Should report a payload violation when a mutation returns a bare result")
  void shouldReportPayloadViolationWhenMutationReturnsBareResult() {
    var fixture =
        mutationFixture("example(input: ExampleInput!): String", conformingPayloadDefinition());

    assertThat(shapeViolations(fixture, fixtureMutation(fixture)))
        .containsExactly("example: expects a nullable ExamplePayload return type");
  }

  @Test
  @DisplayName("Should report a missing payload violation when its type is undeclared")
  void shouldReportMissingPayloadViolationWhenPayloadTypeIsUndeclared() {
    var fixture = mutationFixture("example(input: ExampleInput!): ExamplePayload", "");

    assertThat(shapeViolations(fixture, fixtureMutation(fixture)))
        .containsExactly("example: payload type ExamplePayload is not declared");
  }

  @Test
  @DisplayName("Should report a userErrors violation when its list is nullable")
  void shouldReportUserErrorsViolationWhenItsListIsNullable() {
    var fixture =
        mutationFixture(
            "example(input: ExampleInput!): ExamplePayload",
            """
            type ExamplePayload {
              result: String
              userErrors: [ExampleError!]
            }
            """);

    assertThat(shapeViolations(fixture, fixtureMutation(fixture)))
        .containsExactly("example: payload must declare userErrors: [ExampleError!]!");
  }

  @Test
  @DisplayName("Should report a result violation when a payload exposes multiple result positions")
  void shouldReportResultViolationWhenPayloadExposesMultipleResultPositions() {
    var fixture =
        mutationFixture(
            "example(input: ExampleInput!): ExamplePayload",
            """
            type ExamplePayload {
              firstResult: String
              secondResult: String
              userErrors: [ExampleError!]!
            }
            """);

    assertThat(shapeViolations(fixture, fixtureMutation(fixture)))
        .containsExactly(
            "example: payload must expose exactly one result position plus userErrors");
  }

  @Test
  @DisplayName("Should report an error violation when a union member omits MutationError")
  void shouldReportErrorViolationWhenUnionMemberOmitsMutationError() {
    var fixture =
        new SchemaParser()
            .parse(
                """
                interface MutationError {
                  message: String!
                }

                interface InputMutationError implements MutationError {
                  message: String!
                  inputPath: [String!]!
                }

                type ExampleError {
                  message: String!
                }

                union ExampleMutationError = ExampleError
                """);

    assertThat(errorInterfaceViolations(fixture))
        .containsExactly("ExampleError must implement MutationError");
  }

  @Test
  @DisplayName(
      "Should report an input error violation when an addLibrary error omits InputMutationError")
  void shouldReportInputErrorViolationWhenAddLibraryErrorOmitsInputMutationError() {
    var fixture =
        new SchemaParser()
            .parse(
                """
                interface MutationError {
                  message: String!
                }

                interface InputMutationError implements MutationError {
                  message: String!
                  inputPath: [String!]!
                }

                type LibraryNameRequiredError implements MutationError {
                  message: String!
                  inputPath: [String!]!
                }

                union AddLibraryError = LibraryNameRequiredError
                """);

    assertThat(errorInterfaceViolations(fixture))
        .containsExactly("LibraryNameRequiredError must implement InputMutationError");
  }

  private static List<String> shapeViolations(FieldDefinition mutation) {
    return shapeViolations(SCHEMA, mutation);
  }

  private static List<String> shapeViolations(
      TypeDefinitionRegistry schema, FieldDefinition mutation) {
    var violations = new ArrayList<String>();
    var name = mutation.getName();
    var expectedInput = capitalize(name) + "Input";
    var expectedPayload = capitalize(name) + "Payload";

    var arguments = mutation.getInputValueDefinitions();
    if (arguments.size() != 1
        || !"input".equals(arguments.getFirst().getName())
        || !isRequiredNamed(arguments.getFirst(), expectedInput)) {
      violations.add(name + ": expects exactly one argument input: " + expectedInput + "!");
    }

    if (!(mutation.getType() instanceof TypeName payloadType)
        || !payloadType.getName().equals(expectedPayload)) {
      violations.add(name + ": expects a nullable " + expectedPayload + " return type");
      return violations;
    }

    var payload = schema.getType(payloadType.getName(), ObjectTypeDefinition.class);
    if (payload.isEmpty()) {
      violations.add(name + ": payload type " + expectedPayload + " is not declared");
      return violations;
    }

    var userErrors =
        payload.get().getFieldDefinitions().stream()
            .filter(field -> "userErrors".equals(field.getName()))
            .findFirst();
    if (userErrors.isEmpty()
        || !isNonNullListOfNonNullUnion(
            schema, userErrors.get().getType(), capitalize(name) + "Error")) {
      violations.add(name + ": payload must declare userErrors: [" + capitalize(name) + "Error!]!");
    }

    if (payload.get().getFieldDefinitions().size() != 2) {
      violations.add(name + ": payload must expose exactly one result position plus userErrors");
    }

    return violations;
  }

  private static boolean isRequiredNamed(InputValueDefinition argument, String typeName) {
    return argument.getType() instanceof NonNullType nonNull
        && nonNull.getType() instanceof TypeName named
        && named.getName().equals(typeName);
  }

  private static boolean isNonNullListOfNonNullUnion(
      TypeDefinitionRegistry schema, Type<?> type, String expectedUnion) {
    if (!(type instanceof NonNullType outer && outer.getType() instanceof ListType list)) {
      return false;
    }

    if (!(list.getType() instanceof NonNullType inner
        && inner.getType() instanceof TypeName named)) {
      return false;
    }

    return schema.getType(named.getName(), UnionTypeDefinition.class).isPresent()
        && named.getName().equals(expectedUnion);
  }

  private static List<FieldDefinition> mutationFields() {
    return SCHEMA
        .getType("Mutation", ObjectTypeDefinition.class)
        .orElseThrow()
        .getFieldDefinitions();
  }

  private static List<UnionTypeDefinition> errorUnions(TypeDefinitionRegistry schema) {
    return schema.getTypes(UnionTypeDefinition.class).stream()
        .filter(union -> union.getName().endsWith("Error"))
        .toList();
  }

  private static List<String> errorInterfaceViolations(TypeDefinitionRegistry schema) {
    var violations = new ArrayList<String>();
    var mutationError = schema.getType("MutationError", InterfaceTypeDefinition.class);
    if (mutationError.isEmpty()
        || !fieldNames(mutationError.get().getFieldDefinitions()).equals(List.of("message"))) {
      violations.add("MutationError must declare only message");
    }

    var inputMutationError = schema.getType("InputMutationError", InterfaceTypeDefinition.class);
    if (inputMutationError.isEmpty()
        || !Set.copyOf(fieldNames(inputMutationError.get().getFieldDefinitions()))
            .equals(Set.of("message", "inputPath"))
        || !interfaceNames(inputMutationError.get().getImplements())
            .equals(List.of("MutationError"))) {
      violations.add(
          "InputMutationError must implement MutationError and declare message, inputPath");
    }

    for (var union : errorUnions(schema)) {
      for (var member : union.getMemberTypes()) {
        var type = schema.getType(member, ObjectTypeDefinition.class).orElseThrow();
        var implemented = interfaceNames(type.getImplements());
        if (!implemented.contains("MutationError")) {
          violations.add(type.getName() + " must implement MutationError");
        }

        if (INPUT_ERROR_UNIONS.contains(union.getName())
            && !implemented.contains("InputMutationError")) {
          violations.add(type.getName() + " must implement InputMutationError");
        }

        if (implemented.contains("InputMutationError")
            && !fieldNames(type.getFieldDefinitions())
                .containsAll(List.of("message", "inputPath"))) {
          violations.add(type.getName() + " must declare message and inputPath");
        }
      }
    }

    return violations;
  }

  private static TypeDefinitionRegistry mutationFixture(
      String mutationField, String payloadDefinition) {
    return new SchemaParser()
        .parse(
            """
            input ExampleInput {
              value: String
            }

            interface MutationError {
              message: String!
            }

            interface InputMutationError implements MutationError {
              message: String!
              inputPath: [String!]!
            }

            type ExampleInputError implements InputMutationError & MutationError {
              message: String!
              inputPath: [String!]!
            }

            union ExampleError = ExampleInputError

            %s

            type FixtureMutation {
              %s
            }
            """
                .formatted(payloadDefinition, mutationField));
  }

  private static String conformingPayloadDefinition() {
    return """
        type ExamplePayload {
          result: String
          userErrors: [ExampleError!]!
        }
        """;
  }

  private static FieldDefinition fixtureMutation(TypeDefinitionRegistry fixture) {
    return fixture
        .getType("FixtureMutation", ObjectTypeDefinition.class)
        .orElseThrow()
        .getFieldDefinitions()
        .getFirst();
  }

  private static List<String> fieldNames(List<FieldDefinition> fields) {
    return fields.stream().map(FieldDefinition::getName).toList();
  }

  private static List<String> interfaceNames(List<Type> implemented) {
    return implemented.stream().map(type -> ((TypeName) type).getName()).toList();
  }

  private static String descriptionOf(String typeName) {
    return SCHEMA
        .getType(typeName, ObjectTypeDefinition.class)
        .orElseThrow()
        .getDescription()
        .getContent();
  }

  private static String capitalize(String name) {
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
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
