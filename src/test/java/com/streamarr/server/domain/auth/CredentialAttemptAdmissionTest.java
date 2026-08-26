package com.streamarr.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Credential Attempt Admission Tests")
class CredentialAttemptAdmissionTest {

  @Test
  @DisplayName("Should reject a retry delay that is not positive when blocking an attempt")
  void shouldRejectRetryDelayThatIsNotPositiveWhenBlockingAttempt() {
    assertThatThrownBy(() -> new CredentialAttemptAdmission.Blocked(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("retryAfter must be positive");
    assertThatThrownBy(() -> new CredentialAttemptAdmission.Blocked(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("retryAfter must be positive");
  }
}
