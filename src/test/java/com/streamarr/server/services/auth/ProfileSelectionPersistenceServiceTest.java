package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Profile Selection Persistence Service Tests")
class ProfileSelectionPersistenceServiceTest {

  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final ProfileSelectionPersistenceService service =
      new ProfileSelectionPersistenceService(sessionRepository);

  @Test
  @DisplayName("Should persist profile selection when session is live for account")
  void shouldPersistProfileSelectionWhenSessionIsLiveForAccount() {
    var accountId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    var session = sessionRepository.save(AuthSession.builder().accountId(accountId).build());

    var selected = service.select(accountId, session.getId(), profileId);

    assertThat(selected.getActiveProfileId()).isEqualTo(profileId);
    assertThat(sessionRepository.findById(session.getId()).orElseThrow().getActiveProfileId())
        .isEqualTo(profileId);
  }

  @Test
  @DisplayName("Should reject profile selection when locked session is not live for account")
  void shouldRejectProfileSelectionWhenLockedSessionIsNotLiveForAccount() {
    var accountId = UUID.randomUUID();
    var otherAccountSession =
        sessionRepository.save(AuthSession.builder().accountId(UUID.randomUUID()).build());
    var revokedSession =
        sessionRepository.save(
            AuthSession.builder().accountId(accountId).revokedAt(Instant.EPOCH).build());
    var profileId = UUID.randomUUID();
    var missingSessionId = UUID.randomUUID();
    var otherAccountSessionId = otherAccountSession.getId();
    var revokedSessionId = revokedSession.getId();

    assertThatThrownBy(() -> service.select(accountId, missingSessionId, profileId))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(() -> service.select(accountId, otherAccountSessionId, profileId))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(() -> service.select(accountId, revokedSessionId, profileId))
        .isInstanceOf(AuthenticationRequiredException.class);

    assertThat(otherAccountSession.getActiveProfileId()).isNull();
    assertThat(revokedSession.getActiveProfileId()).isNull();
  }
}
