package com.streamarr.server.controllers.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@Tag("UnitTest")
@DisplayName("Auth Exception Handler Tests")
class AuthExceptionHandlerTest {

  private final AuthExceptionHandler handler = new AuthExceptionHandler();

  @Test
  @DisplayName("Should respond 401 authentication required when identity missing")
  void shouldRespond401AuthenticationRequiredWhenIdentityMissing() {
    var response = handler.handleAuthenticationRequired(new AuthenticationRequiredException());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody().code()).isEqualTo("AUTHENTICATION_REQUIRED");
  }

  @Test
  @DisplayName("Should respond 400 household required when no household selected")
  void shouldRespond400HouseholdRequiredWhenNoHouseholdSelected() {
    var response = handler.handleHouseholdRequired(new HouseholdRequiredException());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().code()).isEqualTo("HOUSEHOLD_REQUIRED");
  }

  @Test
  @DisplayName("Should respond 403 household access denied when membership missing")
  void shouldRespond403HouseholdAccessDeniedWhenMembershipMissing() {
    var response = handler.handleHouseholdDenied(new HouseholdAccessDeniedException());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getBody().code()).isEqualTo("HOUSEHOLD_ACCESS_DENIED");
  }
}
