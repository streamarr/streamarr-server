package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.services.auth.OpaqueOneTimeCodes;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** What the schema itself guarantees about credential rows, independent of any service. */
@Tag("IntegrationTest")
@DisplayName("Credential Invariants Integration Tests")
class CredentialInvariantsIT extends AbstractIntegrationTest {

  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private OpaqueOneTimeCodes opaqueCodes;

  @Test
  @DisplayName("Should invalidate outstanding credentials when their issuer is deleted")
  void shouldInvalidateOutstandingCredentialsWhenIssuerIsDeleted() {
    // Separate Households: deleting the issuer must not take the invitation's target with it.
    var issuer = authTestSupport.createAccount();
    var target = authTestSupport.createAccount();
    var invitation = savePendingInvitation(target, issuer, Instant.now().plus(Duration.ofDays(7)));
    var resetCode = savePendingResetCode(target, issuer, Instant.now().plus(Duration.ofHours(1)));

    try {
      authTestSupport.deleteAccount(issuer.getId());

      var orphanedInvitation = invitationRepository.findById(invitation.getId()).orElseThrow();
      assertThat(orphanedInvitation.getIssuerAccountId()).isNull();
      assertThat(orphanedInvitation.getStatus()).isEqualTo(AccountInvitationStatus.INVALIDATED);
      assertThat(orphanedInvitation.getInvalidationReason()).isEqualTo("issuer deleted");
      assertThat(orphanedInvitation.getDecidedAt()).isNotNull();
      var orphanedCode = resetCodeRepository.findById(resetCode.getId()).orElseThrow();
      assertThat(orphanedCode.getIssuerAccountId()).isNull();
      assertThat(orphanedCode.getStatus()).isEqualTo(PasswordResetCodeStatus.INVALIDATED);
      assertThat(orphanedCode.getInvalidationReason()).isEqualTo("issuer deleted");
    } finally {
      invitationRepository.deleteById(invitation.getId());
      authTestSupport.deleteAccount(target.getId());
    }
  }

  @Test
  @DisplayName("Should leave an expired invitation out of issuer invalidation")
  void shouldLeaveExpiredInvitationOutOfIssuerInvalidation() {
    var issuer = authTestSupport.createAccount();
    var target = authTestSupport.createAccount();
    var expired = savePendingInvitation(target, issuer, Instant.now().minus(Duration.ofHours(1)));

    try {
      var affected =
          invitationRepository.invalidatePendingInvitationsIssuedBy(
              issuer.getId(), "issuer disabled", Instant.now());

      assertThat(affected).isZero();
      var row = invitationRepository.findById(expired.getId()).orElseThrow();
      assertThat(row.getStatus()).isEqualTo(AccountInvitationStatus.PENDING);
      assertThat(row.statusAt(Instant.now())).isEqualTo(AccountInvitationStatus.EXPIRED);
      assertThat(row.getInvalidationReason()).isNull();
    } finally {
      invitationRepository.deleteById(expired.getId());
      authTestSupport.deleteAccount(target.getId());
      authTestSupport.deleteAccount(issuer.getId());
    }
  }

  @Test
  @DisplayName("Should leave an expired reset code out of issuer invalidation")
  void shouldLeaveExpiredResetCodeOutOfIssuerInvalidation() {
    var issuer = authTestSupport.createAccount();
    var target = authTestSupport.createAccount();
    var expired = savePendingResetCode(target, issuer, Instant.now().minus(Duration.ofHours(1)));

    try {
      var affected =
          resetCodeRepository.invalidatePendingPasswordResetCodesIssuedBy(
              issuer.getId(), "issuer disabled", Instant.now());

      assertThat(affected).isZero();
      var row = resetCodeRepository.findById(expired.getId()).orElseThrow();
      assertThat(row.getStatus()).isEqualTo(PasswordResetCodeStatus.PENDING);
      assertThat(row.statusAt(Instant.now())).isEqualTo(PasswordResetCodeStatus.EXPIRED);
      assertThat(row.getInvalidationReason()).isNull();
    } finally {
      authTestSupport.deleteAccount(target.getId());
      authTestSupport.deleteAccount(issuer.getId());
    }
  }

  @Test
  @DisplayName("Should record when an invitation expired when replacement materializes it")
  void shouldRecordWhenInvitationExpiredWhenReplacementMaterializesIt() {
    var issuer = authTestSupport.createAccount();
    var target = authTestSupport.createAccount();
    var expired = savePendingInvitation(target, issuer, Instant.now().minus(Duration.ofHours(1)));

    try {
      var affected =
          invitationRepository.expirePendingInvitationsForRecipientEmail(
              expired.getRecipientEmail(), Instant.now());

      assertThat(affected).isOne();
      var row = invitationRepository.findById(expired.getId()).orElseThrow();
      assertThat(row.getStatus()).isEqualTo(AccountInvitationStatus.EXPIRED);
      assertThat(row.getDecidedAt()).isNotNull();
    } finally {
      invitationRepository.deleteById(expired.getId());
      authTestSupport.deleteAccount(target.getId());
      authTestSupport.deleteAccount(issuer.getId());
    }
  }

  private AccountInvitation savePendingInvitation(
      UserAccount target, UserAccount issuer, Instant expiresAt) {
    var issued = opaqueCodes.issue();
    return invitationRepository.saveAndFlush(
        AccountInvitation.builder()
            .recipientEmail("invitee-" + UUID.randomUUID() + "@example.com")
            .householdId(target.getHouseholdId())
            .householdName("Target Household")
            .householdRole(HouseholdRole.MEMBER)
            .profileName("Invitee")
            .profileKind(ProfileKind.ADULT)
            .issuerAccountId(issuer.getId())
            .expiresAt(expiresAt)
            .publicId(issued.publicId())
            .secretDigest(issued.digest())
            .build());
  }

  private PasswordResetCode savePendingResetCode(
      UserAccount target, UserAccount issuer, Instant expiresAt) {
    var issued = opaqueCodes.issue();
    return resetCodeRepository.saveAndFlush(
        PasswordResetCode.builder()
            .accountId(target.getId())
            .issuerAccountId(issuer.getId())
            .expiresAt(expiresAt)
            .publicId(issued.publicId())
            .secretDigest(issued.digest())
            .build());
  }
}
