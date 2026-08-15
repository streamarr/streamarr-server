package com.streamarr.server.graphql.resolvers;

import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.TypeRuntimeWiring;
import java.io.IOException;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

@Tag("UnitTest")
@DisplayName("Portable Identity Discovery Schema Tests")
class PortableIdentityDiscoverySchemaTest {

  @Test
  @DisplayName("Should expose the identifiers needed to invoke portable identity mutations")
  void shouldExposeIdentifiersNeededToInvokePortableIdentityMutations() throws IOException {
    var schema = schema();
    var me = schema.getObjectType("Me");
    var query = schema.getQueryType();
    var softly = new SoftAssertions();

    softly
        .assertThat(me.getFieldDefinition("homeHouseholdId"))
        .as("the signed-in account's current household ID")
        .isNotNull();
    softly
        .assertThat(me.getFieldDefinition("householdRole"))
        .as("the signed-in account's current household role")
        .isNotNull();
    softly
        .assertThat(query.getFieldDefinitions())
        .as("a query that discovers profile-share IDs")
        .anyMatch(field -> hasUnwrappedType(field.getType(), "PortableProfileShare"));
    softly
        .assertThat(query.getFieldDefinitions())
        .as("a query that discovers manager-invitation IDs")
        .anyMatch(field -> hasUnwrappedType(field.getType(), "PortableProfileManagerInvitation"));
    softly
        .assertThat(query.getFieldDefinitions())
        .as("a query that discovers profile managers and their account IDs")
        .anyMatch(field -> hasUnwrappedType(field.getType(), "PortableProfileManager"));

    softly.assertAll();
  }

  private GraphQLSchema schema() throws IOException {
    var registry = new TypeDefinitionRegistry();
    var parser = new SchemaParser();
    var resources =
        new PathMatchingResourcePatternResolver().getResources("classpath*:schema/*.graphqls");
    for (var resource : resources) {
      try (var input = resource.getInputStream()) {
        registry.merge(parser.parse(input));
      }
    }
    var wiring = RuntimeWiring.newRuntimeWiring();
    for (var abstractType : new String[] {"BaseCollectable", "Media", "ContinueWatchingMedia"}) {
      wiring.type(TypeRuntimeWiring.newTypeWiring(abstractType).typeResolver(environment -> null));
    }
    return new SchemaGenerator().makeExecutableSchema(registry, wiring.build());
  }

  private boolean hasUnwrappedType(GraphQLType type, String expectedName) {
    return GraphQLTypeUtil.unwrapAll(type) instanceof GraphQLNamedType namedType
        && namedType.getName().equals(expectedName);
  }
}
