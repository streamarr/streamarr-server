package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Guards the refresh/login context paths against the same lost-update the FOR UPDATE lock guards
 * the selection endpoints against: a session revoked (logout, password change, reuse detection)
 * between the operation that loaded the session entity and the context write must stay revoked —
 * the write touches only selection columns and must never resurrect revoked_at or regress
 * session_version.
 */
@Tag("IntegrationTest")
@DisplayName("Session Scope Revocation Safety Integration Tests")
class SessionScopeRevocationSafetyIT extends AbstractIntegrationTest {

  @Autowired private SessionScopeService sessionScopeService;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private HouseholdMembershipRepository membershipRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private AccountProfileRepository accountProfileRepository;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  private UserAccount account;

  @AfterEach
  void deleteAccountAndCascades() {
    if (account != null) {
      userAccountRepository.deleteById(account.getId());
    }
  }

  @Test
  @DisplayName("Should keep the session revoked when autoSelectContext runs on a stale entity")
  void shouldKeepSessionRevokedWhenAutoSelectContextRunsOnStaleEntity() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    membershipRepository.save(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.OWNER)
            .build());
    var session =
        authSessionRepository.save(
            AuthSession.builder().accountId(account.getId()).deviceName("login-device").build());

    // The entity login/setup hands to autoSelectContext was loaded in a prior, committed
    // transaction — reload it detached to reproduce that stale snapshot.
    var staleSession = authSessionRepository.findById(session.getId()).orElseThrow();
    var revokedVersion = revoke(session.getId());

    assertThatThrownBy(() -> sessionScopeService.autoSelectContext(account, staleSession))
        .isInstanceOf(AuthenticationRequiredException.class);

    assertRevocationSurvived(session.getId(), revokedVersion);
  }

  @Test
  @DisplayName(
      "Should keep the session revoked when refresh revalidation downgrades a stale entity")
  void shouldKeepSessionRevokedWhenRevalidationDowngradesStaleEntity() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    membershipRepository.save(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.OWNER)
            .build());
    var profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    accountProfileRepository.linkProfile(
        AccountProfile.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .profileId(profile.getId())
            .build());
    var session =
        authSessionRepository.save(
            AuthSession.builder()
                .accountId(account.getId())
                .deviceName("refresh-device")
                .activeHouseholdId(household.getId())
                .activeProfileId(profile.getId())
                .build());

    // Refresh hands revalidateStoredContext the session redeem() loaded — reload detached.
    var staleSession = authSessionRepository.findById(session.getId()).orElseThrow();
    // The profile grant is revoked, so revalidation must downgrade profile scope (a write path).
    revokeLink(account, household.getId(), profile.getId());
    var revokedVersion = revoke(session.getId());

    assertThatThrownBy(() -> sessionScopeService.revalidateStoredContext(account, staleSession))
        .isInstanceOf(AuthenticationRequiredException.class);

    assertRevocationSurvived(session.getId(), revokedVersion);
  }

  private void assertRevocationSurvived(java.util.UUID sessionId, long revokedVersion) {
    var reloaded = authSessionRepository.findById(sessionId).orElseThrow();
    assertThat(reloaded.getRevokedAt()).isNotNull();
    assertThat(reloaded.getRevokedReason()).isEqualTo(SessionRevocationReason.LOGOUT);
    assertThat(reloaded.getSessionVersion()).isEqualTo(revokedVersion);
  }

  private long revoke(java.util.UUID sessionId) {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ ->
                authSessionRepository
                    .revoke(sessionId, SessionRevocationReason.LOGOUT, Instant.now())
                    .orElseThrow());
  }

  private void revokeLink(UserAccount owner, java.util.UUID householdId, java.util.UUID profileId) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ ->
                accountProfileRepository.revokeProfileLink(
                    AccountProfile.builder()
                        .accountId(owner.getId())
                        .householdId(householdId)
                        .profileId(profileId)
                        .build()));
  }
}
