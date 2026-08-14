package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.services.auth.HouseholdAdministrationService;
import com.streamarr.server.services.auth.PortableIdentityMutationService;
import com.streamarr.server.services.auth.ProfileDeletionService;
import com.streamarr.server.services.auth.ProfileManagementService;
import com.streamarr.server.services.auth.ProfilePinService;
import com.streamarr.server.services.auth.ProfilePolicyService;
import com.streamarr.server.services.auth.ProfileSharingService;
import com.streamarr.server.services.auth.ServerAdministrationService;
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
  @MockitoBean private PortableIdentityMutationService mutationService;
  @MockitoBean private ProfileSharingService sharingService;
  @MockitoBean private ProfileManagementService managementService;
  @MockitoBean private ProfilePolicyService policyService;
  @MockitoBean private ProfileDeletionService deletionService;
  @MockitoBean private ServerAdministrationService serverAdministrationService;
  @MockitoBean private HouseholdAdministrationService householdAdministrationService;
  @MockitoBean private ProfilePinService profilePinService;

  @BeforeEach
  void executeTransactionsSynchronously() {
    when(authorizationService.requireAccountId()).thenReturn(UUID.randomUUID());
    when(profilePinService.encode(any())).thenReturn("encoded-pin");
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(0).run();
              return null;
            })
        .when(mutationService)
        .execute(any(Runnable.class));
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
