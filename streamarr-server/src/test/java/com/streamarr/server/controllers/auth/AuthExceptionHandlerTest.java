package com.streamarr.server.controllers.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.SetupAlreadyCompletedException;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@Tag("UnitTest")
@DisplayName("Auth Exception Handler Tests")
class AuthExceptionHandlerTest {

  private final AuthExceptionHandler handler = new AuthExceptionHandler();

  @Test
  @DisplayName("Should respond 409 setup already completed when server claimed")
  void shouldRespond409SetupAlreadyCompletedWhenServerClaimed() {
    var response = handler.handleSetupAlreadyCompleted(new SetupAlreadyCompletedException());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody())
        .isEqualTo(
            new AuthErrorResponse(
                "SETUP_ALREADY_COMPLETED", "Server setup has already been completed."));
  }

  @Test
  @DisplayName("Should respond 401 invalid credentials when login fails")
  void shouldRespond401InvalidCredentialsWhenLoginFails() {
    var response = handler.handleInvalidCredentials(new InvalidCredentialsException());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody())
        .isEqualTo(new AuthErrorResponse("INVALID_CREDENTIALS", "Invalid email or password."));
  }

  @Test
  @DisplayName("Should respond 429 too many attempts when login throttled")
  void shouldRespond429TooManyAttemptsWhenLoginThrottled() {
    var response = handler.handleTooManyAttempts(new TooManyLoginAttemptsException());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getBody())
        .isEqualTo(
            new AuthErrorResponse(
                "TOO_MANY_ATTEMPTS", "Too many failed login attempts. Try again later."));
  }

  @Test
  @DisplayName("Should respond 401 with one body when any refresh token rejected")
  void shouldRespond401WithOneBodyWhenAnyRefreshTokenRejected() {
    var response = handler.handleInvalidRefresh();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody())
        .isEqualTo(
            new AuthErrorResponse(
                "INVALID_REFRESH_TOKEN", "The refresh token is unknown or expired."));
  }

  @Test
  @DisplayName("Should respond 401 authentication required when identity missing")
  void shouldRespond401AuthenticationRequiredWhenIdentityMissing() {
    var response = handler.handleAuthenticationRequired(new AuthenticationRequiredException());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody())
        .isEqualTo(new AuthErrorResponse("AUTHENTICATION_REQUIRED", "Authentication is required."));
  }

  @Test
  @DisplayName("Should respond 400 household required when no household selected")
  void shouldRespond400HouseholdRequiredWhenNoHouseholdSelected() {
    var response = handler.handleHouseholdRequired(new HouseholdRequiredException());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody())
        .isEqualTo(
            new AuthErrorResponse(
                "HOUSEHOLD_REQUIRED", "A household must be selected for this operation."));
  }

  @Test
  @DisplayName("Should respond 403 household access denied when membership missing")
  void shouldRespond403HouseholdAccessDeniedWhenMembershipMissing() {
    var response = handler.handleHouseholdDenied(new HouseholdAccessDeniedException());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody())
        .isEqualTo(
            new AuthErrorResponse(
                "HOUSEHOLD_ACCESS_DENIED",
                "The requested household is not accessible to this account."));
  }

  @Test
  @DisplayName("Should respond 403 profile access denied when profile inaccessible")
  void shouldRespond403ProfileAccessDeniedWhenProfileInaccessible() {
    var response = handler.handleProfileDenied(new ProfileAccessDeniedException());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody())
        .isEqualTo(
            new AuthErrorResponse(
                "PROFILE_ACCESS_DENIED",
                "The requested profile is not accessible to this account."));
  }
}
