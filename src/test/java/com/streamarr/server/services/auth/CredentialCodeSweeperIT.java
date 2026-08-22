package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Credential Code Sweeper Integration Tests")
class CredentialCodeSweeperIT extends AbstractIntegrationTest {

  @Autowired private CredentialCodeSweeper sweeper;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private AuthTestSupport authTestSupport;

  private AuthTestSupport.TestIdentity identity;
  private AuthTestSupport.TestIdentity activeResetIdentity;

  @AfterEach
  void tearDown() {
    invitationRepository.deleteAll();
    resetCodeRepository.deleteAll();
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
    if (activeResetIdentity != null) {
      authTestSupport.deleteIdentity(activeResetIdentity);
    }
  }

  @Test
  @DisplayName("Should expire only stale pending codes when the sweep runs")
  void shouldExpireOnlyStalePendingCodesWhenSweepRuns() {
    identity = authTestSupport.createAdminIdentity();
    activeResetIdentity = authTestSupport.createIdentity();
    var expiredInvitation = saveInvitation(Instant.now().minus(Duration.ofMinutes(1)));
    var activeInvitation = saveInvitation(Instant.now().plus(Duration.ofHours(1)));
    var expiredReset = saveResetCode(identity, Instant.now().minus(Duration.ofMinutes(1)));
    var activeReset = saveResetCode(activeResetIdentity, Instant.now().plus(Duration.ofHours(1)));

    sweeper.sweep();

    assertThat(invitationRepository.findById(expiredInvitation.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.EXPIRED);
    assertThat(invitationRepository.findById(activeInvitation.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.PENDING);
    assertThat(resetCodeRepository.findById(expiredReset.getId()).orElseThrow().getStatus())
        .isEqualTo(PasswordResetCodeStatus.EXPIRED);
    assertThat(resetCodeRepository.findById(activeReset.getId()).orElseThrow().getStatus())
        .isEqualTo(PasswordResetCodeStatus.PENDING);
  }

  private AccountInvitation saveInvitation(Instant expiresAt) {
    return invitationRepository.saveAndFlush(
        AccountInvitation.builder()
            .recipientEmail(UUID.randomUUID() + "@example.com")
            .householdId(identity.household().getId())
            .householdName(identity.household().getName())
            .householdRole(HouseholdRole.MEMBER)
            .profileName("Invitee")
            .profileKind(ProfileKind.ADULT)
            .issuerAccountId(identity.account().getId())
            .expiresAt(expiresAt)
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[32])
            .build());
  }

  private PasswordResetCode saveResetCode(AuthTestSupport.TestIdentity target, Instant expiresAt) {
    return resetCodeRepository.saveAndFlush(
        PasswordResetCode.builder()
            .accountId(target.account().getId())
            .issuerAccountId(identity.account().getId())
            .expiresAt(expiresAt)
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[32])
            .build());
  }
}
