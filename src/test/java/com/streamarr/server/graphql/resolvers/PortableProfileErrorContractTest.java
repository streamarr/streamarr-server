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
import com.streamarr.server.services.auth.PortableIdentityService;
import com.streamarr.server.services.auth.ProfilePinService;
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
  @MockitoBean private PortableIdentityService portableIdentityService;
  @MockitoBean private ProfilePinService profilePinService;

  @BeforeEach
  void authenticateAccount() {
    when(authorizationService.requireAccountId()).thenReturn(UUID.randomUUID());
  }

  @Test
  @DisplayName("Should return invalid credentials code when profile deletion password is wrong")
  void shouldReturnInvalidCredentialsCodeWhenProfileDeletionPasswordIsWrong() {
    doAnswer(_ -> throwException(new InvalidCredentialsException()))
        .when(portableIdentityService)
        .deleteProfile(any());

    var result = dgsQueryExecutor.execute(deleteProfileMutation());

    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().getFirst().getExtensions())
        .containsEntry("code", "INVALID_CREDENTIALS");
  }

  @Test
  @DisplayName("Should return every profile id requiring a PIN in corrective safety payload")
  void shouldReturnEveryProfileIdRequiringAPinInCorrectiveSafetyPayload() {
    var firstProfileId = UUID.randomUUID();
    var secondProfileId = UUID.randomUUID();
    doAnswer(
            _ ->
                throwException(
                    new ProfileSafetyViolationException(List.of(firstProfileId, secondProfileId))))
        .when(portableIdentityService)
        .deleteProfile(any());

    var result = dgsQueryExecutor.execute(deleteProfileMutation());

    assertThat(result.getErrors()).hasSize(1);
    var error = result.getErrors().getFirst();
    assertThat(error.getMessage())
        .doesNotContain(firstProfileId.toString())
        .doesNotContain(secondProfileId.toString());
    assertThat(error.getExtensions()).containsEntry("code", "PROFILE_SAFETY_VIOLATION");
    assertThat(error.getExtensions())
        .containsEntry(
            "profileIds", List.of(firstProfileId.toString(), secondProfileId.toString()));
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
        .when(portableIdentityService)
        .deleteProfile(any());

    var result = dgsQueryExecutor.execute(deleteProfileMutation());

    assertThat(result.getErrors()).hasSize(1);
    var error = result.getErrors().getFirst();
    assertThat(error.getMessage())
        .doesNotContain("DataIntegrityViolationException")
        .doesNotContain("Profiles require a PIN before sharing");
    assertThat(error.getExtensions())
        .containsEntry("code", "PORTABLE_IDENTITY_INVARIANT_VIOLATION");
  }

  @Test
  @DisplayName("Should sanitize non-check-constraint database failures")
  void shouldSanitizeNonCheckConstraintDatabaseFailures() {
    var inaccessibleHouseholdId = UUID.randomUUID();
    var databaseMessage =
        "insert into profile_household_share violates foreign key constraint for household "
            + inaccessibleHouseholdId;
    doAnswer(
            _ -> {
              throw new DataIntegrityViolationException(
                  databaseMessage, new SQLException(databaseMessage, "23503"));
            })
        .when(portableIdentityService)
        .deleteProfile(any());

    var result = dgsQueryExecutor.execute(deleteProfileMutation());

    assertThat(result.getErrors()).hasSize(1);
    var error = result.getErrors().getFirst();
    assertThat(error.getMessage())
        .doesNotContain(databaseMessage)
        .doesNotContain(inaccessibleHouseholdId.toString());
    assertThat(error.getExtensions()).containsEntry("code", "DATABASE_OPERATION_FAILED");
  }

  @Test
  @DisplayName("Should return a client safe validation error when an override reason is blank")
  void shouldReturnClientSafeValidationErrorWhenOverrideReasonIsBlank() {
    doAnswer(_ -> throwException(new IllegalArgumentException("An override reason is required.")))
        .when(portableIdentityService)
        .forceDeleteProfile(any());

    var result = dgsQueryExecutor.execute(forceDeleteProfileMutation());

    assertThat(result.getErrors()).hasSize(1);
    var error = result.getErrors().getFirst();
    assertThat(error.getMessage()).doesNotContain("IllegalArgumentException");
    assertThat(error.getExtensions()).containsEntry("code", "INVALID_INPUT");
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

  private String forceDeleteProfileMutation() {
    return """
        mutation {
          forceDeleteProfile(input: {
            profileId: "%s"
            password: "correct-password"
            reason: " "
          })
        }
        """
        .formatted(UUID.randomUUID());
  }

  private Void throwException(RuntimeException exception) {
    throw exception;
  }
}
