package com.streamarr.server.controllers.auth.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.controllers.auth.AuthErrorResponse;
import com.streamarr.server.exceptions.CredentialAttemptUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@Tag("UnitTest")
@DisplayName("Device Auth Exception Handler Tests")
class DeviceAuthExceptionHandlerTest {

  private final DeviceAuthExceptionHandler handler = new DeviceAuthExceptionHandler();

  @Test
  @DisplayName("Should respond 503 when credential attempt enforcement is unavailable")
  void shouldRespond503WhenCredentialAttemptEnforcementUnavailable() {
    var failure = new CredentialAttemptUnavailableException(new IllegalStateException("offline"));

    var response = handler.handleCredentialAttemptUnavailable(failure);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody())
        .isEqualTo(
            new AuthErrorResponse(
                "CREDENTIAL_VERIFICATION_UNAVAILABLE",
                "Credential verification is temporarily unavailable."));
  }
}
