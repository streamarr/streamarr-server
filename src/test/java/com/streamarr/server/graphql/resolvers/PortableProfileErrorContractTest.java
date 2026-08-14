package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.ProfileSafetyViolationException;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.services.auth.HouseholdAdministrationService;
import com.streamarr.server.services.auth.PortableIdentityMutationService;
import com.streamarr.server.services.auth.ProfileDeletionService;
import com.streamarr.server.services.auth.ProfileManagementService;
import com.streamarr.server.services.auth.ProfilePinService;
import com.streamarr.server.services.auth.ProfilePolicyService;
import com.streamarr.server.services.auth.ProfileSharingService;
import com.streamarr.server.services.auth.ServerAdministrationService;
import com.streamarr.server.services.authorization.AuthorizationService;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(
    classes = {PortableProfileResolver.class, StreamarrDataFetcherExceptionHandler.class})
@DisplayName("Portable Profile GraphQL Error Contract Tests")
class PortableProfileErrorContractTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

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
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(0).run();
              return null;
            })
        .when(mutationService)
        .execute(any(Runnable.class));
  }

  @Test
  @DisplayName("Should return invalid credentials code when profile deletion password is wrong")
  void shouldReturnInvalidCredentialsCodeWhenProfileDeletionPasswordIsWrong() {
    doAnswer(_ -> throwException(new InvalidCredentialsException()))
        .when(deletionService)
        .delete(any());

    var result = dgsQueryExecutor.execute(deleteProfileMutation());

    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().getFirst().getExtensions())
        .containsEntry("code", "INVALID_CREDENTIALS");
  }

  @Test
  @DisplayName("Should return corrective profile safety payload without leaking profile ids")
  void shouldReturnCorrectiveProfileSafetyPayloadWithoutLeakingProfileIds() {
    var inaccessibleProfileId = UUID.randomUUID();
    doAnswer(
            _ ->
                throwException(new ProfileSafetyViolationException(List.of(inaccessibleProfileId))))
        .when(deletionService)
        .delete(any());

    var result = dgsQueryExecutor.execute(deleteProfileMutation());

    assertThat(result.getErrors()).hasSize(1);
    var error = result.getErrors().getFirst();
    assertThat(error.getMessage()).doesNotContain(inaccessibleProfileId.toString());
    assertThat(error.getExtensions())
        .containsEntry("code", "PROFILE_SAFETY_VIOLATION")
        .containsEntry("profilesRequiringPin", List.of(inaccessibleProfileId.toString()));
  }

  @Test
  @DisplayName("Should translate deferred portable identity constraint to client safe error")
  void shouldTranslateDeferredPortableIdentityConstraintToClientSafeError() {
    doAnswer(
            _ -> {
              throw new DataIntegrityViolationException(
                  "Commit failed",
                  new SQLException("Profiles require a PIN before sharing", "23514"));
            })
        .when(mutationService)
        .execute(any(Runnable.class));

    var result = dgsQueryExecutor.execute(deleteProfileMutation());

    assertThat(result.getErrors()).hasSize(1);
    var error = result.getErrors().getFirst();
    assertThat(error.getMessage()).doesNotContain("DataIntegrityViolationException");
    assertThat(error.getExtensions()).containsKey("code");
  }

  private String deleteProfileMutation() {
    return """
        mutation {
          deleteProfile(input: {
            profileId: "%s"
            password: "wrong-password"
          })
        }
        """
        .formatted(UUID.randomUUID());
  }

  private Void throwException(RuntimeException exception) {
    throw exception;
  }
}
