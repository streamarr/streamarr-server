package com.streamarr.server.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.exceptions.InvalidPaginationArgumentException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import graphql.GraphQLError;
import graphql.Scalars;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironmentImpl;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
  @DisplayName("Should map household required to code when household missing")
  void shouldMapHouseholdRequiredToCodeWhenHouseholdMissing() {
    assertThat(codeFor(new HouseholdRequiredException())).isEqualTo("HOUSEHOLD_REQUIRED");
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
  @DisplayName("Should map authorization unavailable when no decision could be made")
  void shouldMapAuthorizationUnavailableWhenNoDecisionCouldBeMade() {
    assertThat(codeFor(new AuthorizationUnavailableException()))
        .isEqualTo("AUTHORIZATION_UNAVAILABLE");
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
  @DisplayName("Should sanitize completion exception when no cause is available")
  void shouldSanitizeCompletionExceptionWhenNoCauseIsAvailable() {
    var error = errorFor(new CompletionException((Throwable) null));

    assertThat(error.getMessage()).isEqualTo("The request could not be completed.");
    assertThat(error.getExtensions())
        .containsEntry("errorType", "INTERNAL")
        .containsEntry("code", "INTERNAL");
  }

  @Test
  @DisplayName("Should sanitize and stamp the contract keys when an exception is unrecognized")
  void shouldSanitizeAndStampContractKeysWhenExceptionIsUnrecognized() {
    var error = errorFor(new IllegalStateException("jdbc: connection refused to 10.0.0.7"));

    assertThat(error.getMessage()).isEqualTo("The request could not be completed.");
    assertThat(error.getExtensions())
        .containsEntry("errorType", "INTERNAL")
        .containsEntry("code", "INTERNAL")
        .containsOnlyKeys("errorType", "code", "requestId");
    assertThat((String) error.getExtensions().get("requestId")).startsWith("req-").hasSize(12);
  }

  @Test
  @DisplayName("Should sanitize illegal argument when an internal value is invalid")
  void shouldSanitizeIllegalArgumentWhenInternalValueIsInvalid() {
    var error = errorFor(new IllegalArgumentException("Invalid filepath URI: file:///srv/media"));

    assertThat(error.getMessage()).isEqualTo("The request could not be completed.");
    assertThat(error.getExtensions())
        .containsEntry("errorType", "INTERNAL")
        .containsEntry("code", "INTERNAL");
  }

  @Test
  @DisplayName("Should stamp errorType, code, and requestId when a gate is classified")
  void shouldStampErrorTypeCodeAndRequestIdWhenGateIsClassified() {
    var error = errorFor(new AccessDeniedException("Not allowed."));

    assertThat(error.getMessage()).isEqualTo("Not allowed.");
    assertThat(error.getExtensions())
        .containsEntry("errorType", "PERMISSION_DENIED")
        .containsEntry("code", "FORBIDDEN")
        .containsOnlyKeys("errorType", "code", "requestId");
  }

  @Test
  @DisplayName("Should classify each gate with its DGS error type when handled")
  void shouldClassifyEachGateWithItsDgsErrorTypeWhenHandled() {
    assertThat(errorTypeFor(new AuthenticationRequiredException())).isEqualTo("UNAUTHENTICATED");
    assertThat(errorTypeFor(new ProfileRequiredException())).isEqualTo("FAILED_PRECONDITION");
    assertThat(errorTypeFor(new HouseholdRequiredException())).isEqualTo("FAILED_PRECONDITION");
    assertThat(errorTypeFor(new AuthorizationUnavailableException())).isEqualTo("UNAVAILABLE");
    assertThat(errorTypeFor(new SessionNotFoundException(UUID.randomUUID())))
        .isEqualTo("NOT_FOUND");
    assertThat(errorTypeFor(new TooManyCredentialAttemptsException())).isEqualTo("UNAVAILABLE");
    assertThat(codeFor(new TooManyCredentialAttemptsException()))
        .isEqualTo("TOO_MANY_CREDENTIAL_ATTEMPTS");
  }

  @Test
  @DisplayName("Should keep the safe ID message when a query ID is invalid")
  void shouldKeepSafeIdMessageWhenQueryIdIsInvalid() {
    var error = errorFor(new InvalidIdException("nope"));

    assertThat(error.getMessage()).isEqualTo("Invalid ID format: nope");
    assertThat(error.getExtensions())
        .containsEntry("errorType", "BAD_REQUEST")
        .containsEntry("code", "INVALID_INPUT");
  }

  @Test
  @DisplayName("Should keep the safe cursor message when a query cursor is invalid")
  void shouldKeepSafeCursorMessageWhenQueryCursorIsInvalid() {
    var error = errorFor(new InvalidCursorException("Cursor filter mismatch: startLetter"));

    assertThat(error.getMessage()).isEqualTo("Cursor filter mismatch: startLetter");
    assertThat(error.getExtensions())
        .containsEntry("errorType", "BAD_REQUEST")
        .containsEntry("code", "INVALID_CURSOR");
  }

  @Test
  @DisplayName("Should keep the safe pagination message when a query page is invalid")
  void shouldKeepSafePaginationMessageWhenQueryPageIsInvalid() {
    var error = errorFor(new InvalidPaginationArgumentException("first must not be negative"));

    assertThat(error.getMessage()).isEqualTo("first must not be negative");
    assertThat(error.getExtensions())
        .containsEntry("errorType", "BAD_REQUEST")
        .containsEntry("code", "INVALID_INPUT");
  }

  @Test
  @DisplayName("Should keep the safe media message when a query media type is unsupported")
  void shouldKeepSafeMediaMessageWhenQueryMediaTypeIsUnsupported() {
    var error = errorFor(new UnsupportedMediaTypeException("OTHER"));

    assertThat(error.getMessage()).isEqualTo("Unsupported media type: OTHER");
    assertThat(error.getExtensions())
        .containsEntry("errorType", "FAILED_PRECONDITION")
        .containsEntry("code", "UNSUPPORTED_MEDIA_TYPE");
  }

  @Test
  @DisplayName("Should add retryAfterSeconds only when the exception knows when to retry")
  void shouldAddRetryAfterSecondsOnlyWhenExceptionKnowsWhenToRetry() {
    var throttled = errorFor(new TooManyDeviceAttemptsException(Duration.ofSeconds(42)));
    var plain = errorFor(new TooManyCredentialAttemptsException());

    assertThat(throttled.getExtensions()).containsEntry("retryAfterSeconds", 42L);
    assertThat(plain.getExtensions()).doesNotContainKey("retryAfterSeconds");
  }

  @Test
  @DisplayName("Should round retry delay up when delay has a fractional second")
  void shouldRoundRetryDelayUpWhenDelayHasFractionalSecond() {
    var error = errorFor(new TooManyDeviceAttemptsException(Duration.ofMillis(1)));

    assertThat(error.getExtensions()).containsEntry("retryAfterSeconds", 1L);
  }

  private String codeFor(Throwable exception) {
    return (String) errorFor(exception).getExtensions().get("code");
  }

  private String errorTypeFor(Throwable exception) {
    return (String) errorFor(exception).getExtensions().get("errorType");
  }

  private GraphQLError errorFor(Throwable exception) {
    var result = handler.handleException(parameters(exception)).join();
    return result.getErrors().getFirst();
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
