package com.streamarr.server.controllers.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("UnitTest")
@DisplayName("Cookie Mode Request Tests")
class CookieModeRequestTest {

  private static final String CREDENTIALS =
      """
      "email": "viewer@example.com", "password": "correct-horse"\
      """;

  private static final String SETUP =
      """
      "email": "admin@example.com", "displayName": "Admin", "password": "correct-horse", \
      "householdName": "Home", "profileName": "Admin"\
      """;

  private static final String INVITATION =
      """
      "code": "invitation-code", "displayName": "Invitee", "password": "correct-horse"\
      """;

  private final JsonMapper mapper = new JsonMapper();

  @Test
  @DisplayName("Should choose bearer delivery when a login request omits cookie mode")
  void shouldChooseBearerDeliveryWhenLoginRequestOmitsCookieMode() {
    var request = mapper.readValue("{%s}".formatted(CREDENTIALS), LoginRequest.class);

    assertThat(request.cookieMode()).isFalse();
  }

  @Test
  @DisplayName("Should choose bearer delivery when a login request sends a null cookie mode")
  void shouldChooseBearerDeliveryWhenLoginRequestSendsNullCookieMode() {
    var request =
        mapper.readValue("{%s, \"cookieMode\": null}".formatted(CREDENTIALS), LoginRequest.class);

    assertThat(request.cookieMode()).isFalse();
  }

  @Test
  @DisplayName("Should choose cookie delivery when a login request asks for cookie mode")
  void shouldChooseCookieDeliveryWhenLoginRequestAsksForCookieMode() {
    var request =
        mapper.readValue("{%s, \"cookieMode\": true}".formatted(CREDENTIALS), LoginRequest.class);

    assertThat(request.cookieMode()).isTrue();
  }

  @Test
  @DisplayName("Should choose bearer delivery when a setup request omits cookie mode")
  void shouldChooseBearerDeliveryWhenSetupRequestOmitsCookieMode() {
    var request = mapper.readValue("{%s}".formatted(SETUP), SetupRequest.class);

    assertThat(request.cookieMode()).isFalse();
  }

  @Test
  @DisplayName("Should choose cookie delivery when a setup request asks for cookie mode")
  void shouldChooseCookieDeliveryWhenSetupRequestAsksForCookieMode() {
    var request =
        mapper.readValue("{%s, \"cookieMode\": true}".formatted(SETUP), SetupRequest.class);

    assertThat(request.cookieMode()).isTrue();
  }

  @Test
  @DisplayName("Should choose bearer delivery when an invitation acceptance omits cookie mode")
  void shouldChooseBearerDeliveryWhenInvitationAcceptanceOmitsCookieMode() {
    var request = mapper.readValue("{%s}".formatted(INVITATION), AcceptInvitationRequest.class);

    assertThat(request.cookieMode()).isFalse();
  }

  @Test
  @DisplayName("Should choose cookie delivery when an invitation acceptance asks for cookie mode")
  void shouldChooseCookieDeliveryWhenInvitationAcceptanceAsksForCookieMode() {
    var request =
        mapper.readValue(
            "{%s, \"cookieMode\": true}".formatted(INVITATION), AcceptInvitationRequest.class);

    assertThat(request.cookieMode()).isTrue();
  }
}
