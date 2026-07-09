package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Tag("IntegrationTest")
@DisplayName("Playback Token Revocation Integration Tests")
class PlaybackTokenRevocationIT extends AbstractIntegrationTest {

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private PasswordChangeService passwordChangeService;
  @Autowired private PlaybackTokenIssuer playbackTokenIssuer;

  @Test
  @DisplayName("Should not mint usable playback token after authenticated authority becomes stale")
  void shouldNotMintUsablePlaybackTokenAfterAuthenticatedAuthorityBecomesStale() {
    var identity = authTestSupport.createIdentity();

    try {
      var authenticatedSource = jwtDecoder.decode(authTestSupport.profileBearer(identity));
      passwordChangeService.changePassword(
          ChangePasswordCommand.builder()
              .accountId(identity.account().getId())
              .sessionId(identity.session().getId())
              .currentPassword(AuthTestSupport.PASSWORD)
              .newPassword("a brand new passphrase!")
              .build());

      var streamSession =
          StreamSession.builder()
              .sessionId(UUID.randomUUID())
              .profileId(identity.profile().getId())
              .build();

      assertThatThrownBy(
              () ->
                  playbackTokenIssuer.issue(
                      AuthenticatedIdentity.fromJwt(authenticatedSource),
                      streamSession,
                      Duration.ofHours(1)))
          .isInstanceOf(AuthenticationRequiredException.class);
    } finally {
      authTestSupport.deleteIdentity(identity);
    }
  }
}
