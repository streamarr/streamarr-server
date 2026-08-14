package com.streamarr.server.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdOwnershipTransferRequiredException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.InvalidProfilePinException;
import com.streamarr.server.exceptions.KidProfileManagerRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.ProfileDeletionBlockedException;
import com.streamarr.server.exceptions.ProfileManagementDeniedException;
import com.streamarr.server.exceptions.ProfileManagerInvariantException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.exceptions.ProfileSafetyViolationException;
import com.streamarr.server.exceptions.ServerAdministrationDeniedException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import graphql.Scalars;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironmentImpl;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Streamarr Data Fetcher Exception Handler Tests")
class StreamarrDataFetcherExceptionHandlerTest {

  private final StreamarrDataFetcherExceptionHandler handler =
      new StreamarrDataFetcherExceptionHandler();

  @Test
  @DisplayName("Should map profile required to code when profile missing")
  void shouldMapProfileRequiredToCodeWhenProfileMissing() {
    assertThat(codeFor(new ProfileRequiredException())).isEqualTo("PROFILE_REQUIRED");
  }

  @Test
  @DisplayName("Should map authentication required to code when identity missing")
  void shouldMapAuthenticationRequiredToCodeWhenIdentityMissing() {
    assertThat(codeFor(new AuthenticationRequiredException())).isEqualTo("AUTHENTICATION_REQUIRED");
  }

  @Test
  @DisplayName("Should map access denied to forbidden when authorization fails")
  void shouldMapAccessDeniedToForbiddenWhenAuthorizationFails() {
    assertThat(codeFor(new AccessDeniedException("denied"))).isEqualTo("FORBIDDEN");
  }

  @Test
  @DisplayName("Should map session not found to code when session missing")
  void shouldMapSessionNotFoundToCodeWhenSessionMissing() {
    // Routine, not internal: the reaper retires idle sessions and ownership misses read as
    // missing — clients key the recreate-session path on this code.
    assertThat(codeFor(new SessionNotFoundException(UUID.randomUUID())))
        .isEqualTo("SESSION_NOT_FOUND");
  }

  @Test
  @DisplayName("Should unwrap completion exception when data loader fails async")
  void shouldUnwrapCompletionExceptionWhenDataLoaderFailsAsync() {
    assertThat(codeFor(new CompletionException(new ProfileRequiredException())))
        .isEqualTo("PROFILE_REQUIRED");
  }

  @Test
  @DisplayName("Should delegate to default handler when exception unrelated to identity")
  void shouldDelegateToDefaultHandlerWhenExceptionUnrelatedToIdentity() {
    assertThat(codeFor(new IllegalStateException("boom"))).isNull();
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("portableIdentityErrors")
  @DisplayName("Should map portable identity domain failures to stable codes")
  void shouldMapPortableIdentityDomainFailuresToStableCodes(
      RuntimeException exception, String expectedCode) {
    assertThat(codeFor(exception)).isEqualTo(expectedCode);
  }

  private static Stream<Arguments> portableIdentityErrors() {
    return Stream.of(
        Arguments.of(new InvalidCredentialsException(), "INVALID_CREDENTIALS"),
        Arguments.of(new InvalidProfilePinException(), "INVALID_PROFILE_PIN"),
        Arguments.of(new HouseholdAccessDeniedException(), "HOUSEHOLD_ACCESS_DENIED"),
        Arguments.of(
            new HouseholdOwnershipTransferRequiredException(),
            "HOUSEHOLD_OWNERSHIP_TRANSFER_REQUIRED"),
        Arguments.of(new ProfileAccessDeniedException(), "PROFILE_ACCESS_DENIED"),
        Arguments.of(new ProfileDeletionBlockedException("blocked"), "PROFILE_DELETION_BLOCKED"),
        Arguments.of(new ProfileManagementDeniedException(), "PROFILE_MANAGEMENT_DENIED"),
        Arguments.of(new ProfileManagerInvariantException("invalid"), "PROFILE_MANAGER_INVARIANT"),
        Arguments.of(
            new ProfileSafetyViolationException(java.util.List.of(UUID.randomUUID())),
            "PROFILE_SAFETY_VIOLATION"),
        Arguments.of(new KidProfileManagerRequiredException(), "KID_PROFILE_MANAGER_REQUIRED"),
        Arguments.of(new ServerAdministrationDeniedException(), "SERVER_ADMINISTRATION_DENIED"));
  }

  // The cause-null arm of unwrap stays untested on purpose: a cause-less CompletionException is
  // only constructible by hand (CompletableFuture always wraps a cause), and DGS's default
  // handler recurses infinitely on one — the guard exists to keep our unwrap out of that state.

  private String codeFor(Throwable exception) {
    var result = handler.handleException(parameters(exception)).join();

    var extensions = result.getErrors().getFirst().getExtensions();
    return extensions == null ? null : (String) extensions.get("code");
  }

  private static DataFetcherExceptionHandlerParameters parameters(Throwable exception) {
    var stepInfo =
        ExecutionStepInfo.newExecutionStepInfo()
            .type(Scalars.GraphQLString)
            .path(ResultPath.parse("/test"))
            .build();
    var environment =
        DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .mergedField(MergedField.newMergedField(Field.newField("test").build()).build())
            .executionStepInfo(stepInfo)
            .build();

    return DataFetcherExceptionHandlerParameters.newExceptionParameters()
        .dataFetchingEnvironment(environment)
        .exception(exception)
        .build();
  }
}
