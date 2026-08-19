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

  private static final TypeDefinitionRegistry SCHEMA = loadSchema();

  @Test
  @DisplayName("Should keep every non-legacy mutation on the ADR 0026 shape")
  void shouldKeepEveryNonLegacyMutationOnAdr0026Shape() {
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
  @DisplayName("Should only shrink the legacy allowlist as mutations migrate")
  void shouldOnlyShrinkLegacyAllowlistAsMutationsMigrate() {
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
  @DisplayName("Should declare every mutation error type through the shared interfaces")
  void shouldDeclareEveryMutationErrorTypeThroughSharedInterfaces() {
    var mutationError =
        SCHEMA.getType("MutationError", InterfaceTypeDefinition.class).orElseThrow();
    var inputMutationError =
        SCHEMA.getType("InputMutationError", InterfaceTypeDefinition.class).orElseThrow();

    assertThat(fieldNames(mutationError.getFieldDefinitions())).containsExactly("message");
    assertThat(fieldNames(inputMutationError.getFieldDefinitions()))
        .containsExactlyInAnyOrder("message", "inputPath");
    assertThat(interfaceNames(inputMutationError.getImplements())).containsExactly("MutationError");

    for (var union : errorUnions()) {
      for (var member : union.getMemberTypes()) {
        var type = SCHEMA.getType(member, ObjectTypeDefinition.class).orElseThrow();
        assertThat(interfaceNames(type.getImplements()))
            .as("%s must implement MutationError", type.getName())
            .contains("MutationError");
        if (interfaceNames(type.getImplements()).contains("InputMutationError")) {
          assertThat(fieldNames(type.getFieldDefinitions())).contains("message", "inputPath");
        }
      }
    }
  }

  private static List<String> shapeViolations(FieldDefinition mutation) {
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
    var payload = SCHEMA.getType(payloadType.getName(), ObjectTypeDefinition.class);
    if (payload.isEmpty()) {
      violations.add(name + ": payload type " + expectedPayload + " is not declared");
      return violations;
    }
    var userErrors =
        payload.get().getFieldDefinitions().stream()
            .filter(field -> "userErrors".equals(field.getName()))
            .findFirst();
    if (userErrors.isEmpty() || !isNonNullListOfNonNullUnion(userErrors.get().getType())) {
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

  private static boolean isNonNullListOfNonNullUnion(Type<?> type) {
    if (!(type instanceof NonNullType outer && outer.getType() instanceof ListType list)) {
      return false;
    }
    if (!(list.getType() instanceof NonNullType inner
        && inner.getType() instanceof TypeName named)) {
      return false;
    }
    return SCHEMA.getType(named.getName(), UnionTypeDefinition.class).isPresent()
        && named.getName().endsWith("Error");
  }

  private static List<FieldDefinition> mutationFields() {
    return SCHEMA
        .getType("Mutation", ObjectTypeDefinition.class)
        .orElseThrow()
        .getFieldDefinitions();
  }

  private static List<UnionTypeDefinition> errorUnions() {
    return SCHEMA.getTypes(UnionTypeDefinition.class).stream()
        .filter(union -> union.getName().endsWith("Error"))
        .toList();
  }

  private static List<String> fieldNames(List<FieldDefinition> fields) {
    return fields.stream().map(FieldDefinition::getName).toList();
  }

  private static List<String> interfaceNames(List<Type> implemented) {
    return implemented.stream().map(type -> ((TypeName) type).getName()).toList();
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
