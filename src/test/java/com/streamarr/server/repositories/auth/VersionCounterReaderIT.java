package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Version Counter Reader Integration Tests")
class VersionCounterReaderIT extends AbstractIntegrationTest {

  @Autowired private VersionCounterReader versionCounterReader;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private HouseholdMembershipRepository membershipRepository;

  @Autowired private ProfileRepository profileRepository;

  @Autowired private AuthSessionRepository authSessionRepository;

  private UserAccount account;
  private UUID householdId;

  @AfterEach
  void deleteIdentityGraph() {
    if (householdId != null) {
      householdRepository.deleteById(householdId);
    }
    if (account != null) {
      userAccountRepository.deleteById(account.getId());
    }
  }

  @Test
  @DisplayName("Should read counters when rows present")
  void shouldReadCountersWhenRowsPresent() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    householdId = household.getId();
    membershipRepository.save(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(householdId)
            .householdRole(HouseholdRole.OWNER)
            .membershipVersion(4)
            .build());
    var profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder()
                .householdId(householdId)
                .policyVersion(6)
                .build());
    var session =
        authSessionRepository.save(
            AuthSession.builder().accountId(account.getId()).sessionVersion(2).build());

    assertThat(versionCounterReader.sessionVersion(session.getId())).contains(2L);
    assertThat(versionCounterReader.membershipVersion(account.getId(), householdId)).contains(4L);
    assertThat(versionCounterReader.profilePolicyVersion(profile.getId())).contains(6L);
  }

  @Test
  @DisplayName("Should read absent when session revoked or rows missing")
  void shouldReadAbsentWhenSessionRevokedOrRowsMissing() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var session =
        authSessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    authSessionRepository.revoke(session.getId(), SessionRevocationReason.LOGOUT, Instant.now());

    // A revoked session reads as absent — fail-closed for any token still carrying its id.
    assertThat(versionCounterReader.sessionVersion(session.getId())).isEmpty();
    assertThat(versionCounterReader.membershipVersion(UUID.randomUUID(), UUID.randomUUID()))
        .isEmpty();
    assertThat(versionCounterReader.profilePolicyVersion(UUID.randomUUID())).isEmpty();
  }
}
