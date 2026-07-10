package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Secret record string representation tests")
class SecretRecordToStringTest {

  @Test
  @DisplayName("Should not expose plaintext secrets in builder string representations")
  void shouldNotExposePlaintextSecretsInBuilderStringRepresentations() {
    var secret = UUID.randomUUID().toString();
    var renderedValues =
        List.of(
            LoginCommand.builder().password(secret).toString(),
            SetupCommand.builder().password(secret).toString(),
            LoginResult.builder().rawRefreshToken(secret).toString(),
            AccessToken.builder().value(secret).toString());

    assertThat(renderedValues)
        .hasSize(4)
        .allSatisfy(rendered -> assertThat(rendered).doesNotContain(secret));
  }

  @Test
  @DisplayName("Should not expose plaintext secrets in string representations")
  void shouldNotExposePlaintextSecretsInStringRepresentations() {
    var secret = "review-secret-value";
    var renderedValues =
        List.of(
            LoginCommand.builder().password(secret).build().toString(),
            SetupCommand.builder().password(secret).build().toString(),
            LoginResult.builder().rawRefreshToken(secret).build().toString(),
            new IssuedRefreshToken(secret, null).toString(),
            new RefreshResult.Rotated(secret, null).toString(),
            new RefreshResult.Replayed(secret, null).toString(),
            AccessToken.builder()
                .value(secret)
                .expiresAt(Instant.EPOCH)
                .scope(TokenScope.ACCOUNT)
                .build()
                .toString());

    assertThat(renderedValues)
        .hasSize(7)
        .allSatisfy(rendered -> assertThat(rendered).doesNotContain(secret));
  }
}
