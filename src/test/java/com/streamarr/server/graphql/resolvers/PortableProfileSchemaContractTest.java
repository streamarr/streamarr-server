package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.services.auth.PortableIdentityService;
import com.streamarr.server.services.auth.ProfilePinService;
import com.streamarr.server.services.authorization.AuthorizationService;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLTypeUtil;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(classes = PortableProfileResolver.class)
@DisplayName("Portable Profile GraphQL Schema Contract Tests")
class PortableProfileSchemaContractTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @Autowired private GraphQlSource graphQlSource;

  @MockitoBean private AuthorizationService authorizationService;
  @MockitoBean private PortableIdentityService portableIdentityService;
  @MockitoBean private ProfilePinService profilePinService;

  @BeforeEach
  void configureGraphQlBoundaryServices() {
    when(authorizationService.requireAccountId()).thenReturn(UUID.randomUUID());
    when(profilePinService.encode(any())).thenReturn("encoded-pin");
  }

  @Test
  @DisplayName("Should bind profile kind changes through the GraphQL schema")
  void shouldBindProfileKindChangesThroughGraphQlSchema() {
    Boolean changed =
        dgsQueryExecutor.executeAndExtractJsonPath(
            """
            mutation {
              setProfileKind(input: {
                profileId: "%s"
                kind: KID
              })
            }
            """
                .formatted(UUID.randomUUID()),
            "data.setProfileKind");

    assertThat(changed).isTrue();
  }

  @Test
  @DisplayName("Should bind profile content ceiling changes through the GraphQL schema")
  void shouldBindProfileContentCeilingChangesThroughGraphQlSchema() {
    Boolean changed =
        dgsQueryExecutor.executeAndExtractJsonPath(
            """
            mutation {
              setProfileContentCeiling(input: {
                profileId: "%s"
                maximumAllowedRatingAge: 13
              })
            }
            """
                .formatted(UUID.randomUUID()),
            "data.setProfileContentCeiling");

    assertThat(changed).isTrue();
  }

  @Test
  @DisplayName("Should bind profile content ceiling removal through the GraphQL schema")
  void shouldBindProfileContentCeilingRemovalThroughGraphQlSchema() {
    Boolean changed =
        dgsQueryExecutor.executeAndExtractJsonPath(
            """
            mutation {
              removeProfileContentCeiling(input: {
                profileId: "%s"
              })
            }
            """
                .formatted(UUID.randomUUID()),
            "data.removeProfileContentCeiling");

    assertThat(changed).isTrue();
  }

  @Test
  @DisplayName("Should bind profile PIN resets through the GraphQL schema")
  void shouldBindProfilePinResetsThroughGraphQlSchema() {
    Boolean changed =
        dgsQueryExecutor.executeAndExtractJsonPath(
            """
            mutation {
              resetProfilePin(input: {
                profileId: "%s"
                newPin: "2468"
              })
            }
            """
                .formatted(UUID.randomUUID()),
            "data.resetProfilePin");

    assertThat(changed).isTrue();
  }

  @Test
  @DisplayName("Should expose portable identity closed values as GraphQL enums")
  void shouldExposePortableIdentityClosedValuesAsGraphQlEnums() {
    assertEnumField("PortableProfileSummary", "kind", "ProfileKind");
    assertEnumField("PortableProfileShare", "status", "ProfileShareStatus");
    assertEnumField("PortableProfileManagerInvitation", "status", "ProfileManagerInvitationStatus");
  }

  private void assertEnumField(String typeName, String fieldName, String enumName) {
    var field = graphQlSource.schema().getObjectType(typeName).getFieldDefinition(fieldName);
    assertThat(GraphQLTypeUtil.unwrapAll(field.getType()))
        .isInstanceOfSatisfying(
            GraphQLEnumType.class, enumType -> assertThat(enumType.getName()).isEqualTo(enumName));
  }
}
