package com.streamarr.server.graphql.contract;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.language.DescribedNode;
import graphql.language.Description;
import graphql.language.EnumTypeDefinition;
import graphql.language.FieldDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Profile management pages build on what the schema says about who may call each field
 * (streamarr-server#907). Every Query and Mutation field about Profiles, their managers or
 * invitations says so, and so does every type such a query returns. The fields are found in the
 * schema files, so a new one is held to the same rule without being listed here.
 */
@Tag("UnitTest")
@DisplayName("Profile Management Schema Description Tests")
class ProfileManagementSchemaDescriptionTest {

  private static final Pattern ABOUT_PROFILE_MANAGEMENT =
      Pattern.compile("profile|manager|invitation", Pattern.CASE_INSENSITIVE);

  private static final TypeDefinitionRegistry SCHEMA = loadSchema();

  @Test
  @DisplayName(
      "Should describe every Profile, manager and invitation root field when the schema is parsed")
  void shouldDescribeEveryProfileManagerAndInvitationRootFieldWhenTheSchemaIsParsed() {
    var undescribed =
        Stream.concat(rootFields("Query"), rootFields("Mutation"))
            .filter(field -> ABOUT_PROFILE_MANAGEMENT.matcher(field.getName()).find())
            .filter(field -> isBlank(field.getDescription()))
            .map(FieldDefinition::getName)
            .toList();

    assertThat(undescribed).isEmpty();
  }

  @Test
  @DisplayName(
      "Should describe every type a Profile, manager or invitation query returns when the schema"
          + " is parsed")
  void shouldDescribeEveryTypeAProfileManagerOrInvitationQueryReturnsWhenTheSchemaIsParsed() {
    var undescribed =
        rootFields("Query")
            .filter(field -> ABOUT_PROFILE_MANAGEMENT.matcher(field.getName()).find())
            .flatMap(field -> returnedTypes(field.getType()))
            .filter(type -> isBlank(((DescribedNode<?>) type).getDescription()))
            .map(TypeDefinition::getName)
            .distinct()
            .toList();

    assertThat(undescribed).isEmpty();
  }

  /** The type a field returns, and for a connection also its edge and its node. */
  private static Stream<TypeDefinition<?>> returnedTypes(Type<?> type) {
    var returned = definitionOf(namedTypeOf(type));
    if (returned.isEmpty()) {
      return Stream.empty();
    }

    var definition = returned.get();
    if (!(definition instanceof ObjectTypeDefinition object)
        || !object.getName().endsWith("Connection")) {
      return Stream.of(definition);
    }

    var edges = fieldNamed(object, "edges").map(edge -> definitionOf(namedTypeOf(edge.getType())));
    var edge = edges.flatMap(found -> found);
    var node =
        edge.filter(ObjectTypeDefinition.class::isInstance)
            .map(ObjectTypeDefinition.class::cast)
            .flatMap(found -> fieldNamed(found, "node"))
            .flatMap(found -> definitionOf(namedTypeOf(found.getType())));
    return Stream.of(Optional.of(definition), edge, node).flatMap(Optional::stream);
  }

  private static Optional<FieldDefinition> fieldNamed(ObjectTypeDefinition type, String name) {
    return type.getFieldDefinitions().stream()
        .filter(field -> field.getName().equals(name))
        .findFirst();
  }

  private static Optional<TypeDefinition<?>> definitionOf(String name) {
    return SCHEMA
        .getType(name)
        .filter(type -> type instanceof ObjectTypeDefinition || type instanceof EnumTypeDefinition)
        .map(type -> (TypeDefinition<?>) type);
  }

  private static String namedTypeOf(Type<?> type) {
    return switch (type) {
      case NonNullType nonNull -> namedTypeOf(nonNull.getType());
      case ListType list -> namedTypeOf(list.getType());
      case TypeName name -> name.getName();
      default -> throw new IllegalStateException("unexpected GraphQL type " + type);
    };
  }

  private static Stream<FieldDefinition> rootFields(String root) {
    var declared =
        SCHEMA.getType(root, ObjectTypeDefinition.class).stream()
            .flatMap(type -> type.getFieldDefinitions().stream());
    var extended =
        SCHEMA.objectTypeExtensions().getOrDefault(root, List.of()).stream()
            .map(ObjectTypeExtensionDefinition.class::cast)
            .flatMap(type -> type.getFieldDefinitions().stream());
    return Stream.concat(declared, extended);
  }

  private static boolean isBlank(Description description) {
    return description == null || description.getContent().isBlank();
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
