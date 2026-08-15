package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Auth Session Profile Selection Integration Tests")
class AuthSessionProfileSelectionIT extends AbstractIntegrationTest {

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private AuthSessionRepository authSessionRepository;

  private final List<TestIdentity> identities = new ArrayList<>();

  @AfterEach
  void deleteIdentities() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should clear only live matching profile selections in target household")
  void shouldClearOnlyLiveMatchingProfileSelectionsInTargetHousehold() {
    var target = identity();
    var otherHousehold = identity();
    var profileId = target.profile().getId();
    var liveTarget = session(target, profileId, null);
    var revokedTarget = session(target, profileId, Instant.EPOCH);
    var differentProfile = session(target, otherHousehold.profile().getId(), null);
    var liveOtherHousehold = session(otherHousehold, profileId, null);

    var cleared =
        authSessionRepository.clearProfileSelection(
            profileId, target.household().getId(), Instant.now());

    assertAll(
        () -> assertThat(cleared).isEqualTo(2),
        () -> assertThat(activeProfileId(target.session())).isNull(),
        () -> assertThat(activeProfileId(liveTarget)).isNull(),
        () -> assertThat(activeProfileId(revokedTarget)).isEqualTo(profileId),
        () ->
            assertThat(activeProfileId(differentProfile))
                .isEqualTo(otherHousehold.profile().getId()),
        () -> assertThat(activeProfileId(liveOtherHousehold)).isEqualTo(profileId));
  }

  /**
   * Creates and tracks a test identity for cleanup after the test.
   *
   * @return the created test identity
   */
  private TestIdentity identity() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);
    return identity;
  }

  /**
   * Creates and persists an authentication session for the specified identity and profile.
   *
   * @param identity the identity associated with the session
   * @param profileId the profile selected in the session
   * @param revokedAt the revocation timestamp, or {@code null} for an active session
   * @return the persisted authentication session
   */
  private AuthSession session(TestIdentity identity, UUID profileId, Instant revokedAt) {
    return authSessionRepository.save(
        AuthSession.builder()
            .accountId(identity.account().getId())
            .activeProfileId(profileId)
            .deviceName("selection-scope-test")
            .revokedAt(revokedAt)
            .revokedReason(revokedAt == null ? null : SessionRevocationReason.ADMIN_REVOCATION)
            .build());
  }

  /**
   * Retrieves the active profile identifier for a persisted authentication session.
   *
   * @param session the authentication session whose active profile is retrieved
   * @return the session's active profile identifier
   */
  private UUID activeProfileId(AuthSession session) {
    return authSessionRepository.findById(session.getId()).orElseThrow().getActiveProfileId();
  }
}
