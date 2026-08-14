package com.streamarr.server.fakes;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.AuthSession;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Fake Auth Session Repository Tests")
class FakeAuthSessionRepositoryTest {

  private final FakeAuthSessionRepository repository = new FakeAuthSessionRepository();

  @Test
  @DisplayName("Should not clear a profile selection for an unrelated household")
  void shouldNotClearProfileSelectionForUnrelatedHousehold() {
    var profileId = UUID.randomUUID();
    var accountId = UUID.randomUUID();
    var session =
        repository.save(
            AuthSession.builder().accountId(accountId).activeProfileId(profileId).build());
    repository.registerAccountHome(accountId, UUID.randomUUID());

    var cleared = repository.clearProfileSelection(profileId, UUID.randomUUID(), Instant.now());

    assertThat(cleared).isZero();
    assertThat(repository.findById(session.getId()).orElseThrow().getActiveProfileId())
        .isEqualTo(profileId);
  }

  @Test
  @DisplayName("Should not clear a revoked session profile selection")
  void shouldNotClearRevokedSessionProfileSelection() {
    var profileId = UUID.randomUUID();
    var accountId = UUID.randomUUID();
    var householdId = UUID.randomUUID();
    var session =
        repository.save(
            AuthSession.builder()
                .accountId(accountId)
                .activeProfileId(profileId)
                .revokedAt(Instant.EPOCH)
                .build());
    repository.registerAccountHome(accountId, householdId);

    var cleared = repository.clearProfileSelection(profileId, householdId, Instant.now());

    assertThat(cleared).isZero();
    assertThat(repository.findById(session.getId()).orElseThrow().getActiveProfileId())
        .isEqualTo(profileId);
  }

  @Test
  @DisplayName("Should clear a live profile selection for the matching household")
  void shouldClearLiveProfileSelectionForMatchingHousehold() {
    var profileId = UUID.randomUUID();
    var accountId = UUID.randomUUID();
    var householdId = UUID.randomUUID();
    var session =
        repository.save(
            AuthSession.builder().accountId(accountId).activeProfileId(profileId).build());
    repository.registerAccountHome(accountId, householdId);

    var cleared = repository.clearProfileSelection(profileId, householdId, Instant.now());

    assertThat(cleared).isOne();
    assertThat(repository.findById(session.getId()).orElseThrow().getActiveProfileId()).isNull();
  }
}
