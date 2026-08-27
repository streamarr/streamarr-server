package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.dao.DataIntegrityViolationException;

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
  @DisplayName("Should preserve an expired invitation when its issuer is deleted")
  void shouldPreserveExpiredInvitationWhenIssuerIsDeleted() {
    var issuer = authTestSupport.createAccount();
    var target = authTestSupport.createAccount();
    var expired = savePendingInvitation(target, issuer, Instant.now().minus(Duration.ofHours(1)));

    try {
      authTestSupport.deleteAccount(issuer.getId());

      var row = invitationRepository.findById(expired.getId()).orElseThrow();
      assertThat(row.getIssuerAccountId()).isNull();
      assertThat(row.getStatus()).isEqualTo(AccountInvitationStatus.PENDING);
      assertThat(row.statusAt(Instant.now())).isEqualTo(AccountInvitationStatus.EXPIRED);
      assertThat(row.getInvalidationReason()).isNull();
      assertThat(row.getDecidedAt()).isNull();
    } finally {
      invitationRepository.deleteById(expired.getId());
      authTestSupport.deleteAccount(target.getId());
    }
  }

  @Test
  @DisplayName("Should preserve an expired reset code when its issuer is deleted")
  void shouldPreserveExpiredResetCodeWhenIssuerIsDeleted() {
    var issuer = authTestSupport.createAccount();
    var target = authTestSupport.createAccount();
    var expired = savePendingResetCode(target, issuer, Instant.now().minus(Duration.ofHours(1)));

    try {
      authTestSupport.deleteAccount(issuer.getId());

      var row = resetCodeRepository.findById(expired.getId()).orElseThrow();
      assertThat(row.getIssuerAccountId()).isNull();
      assertThat(row.getStatus()).isEqualTo(PasswordResetCodeStatus.PENDING);
      assertThat(row.statusAt(Instant.now())).isEqualTo(PasswordResetCodeStatus.EXPIRED);
      assertThat(row.getInvalidationReason()).isNull();
    } finally {
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

  @Test
  @DisplayName("Should reject an invitation whose decision state contradicts its status")
  void shouldRejectInvitationWhoseDecisionStateContradictsItsStatus() {
    var target = authTestSupport.createAccount();
    var accepted =
        pendingInvitationRow(target, target, Instant.now().plus(Duration.ofDays(7)))
            .status(AccountInvitationStatus.ACCEPTED)
            .build();

    try {
      assertThatThrownBy(() -> invitationRepository.saveAndFlush(accepted))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasStackTraceContaining("chk_account_invitation_decided_at");
    } finally {
      invitationRepository
          .findByPublicId(accepted.getPublicId())
          .ifPresent(row -> invitationRepository.deleteById(row.getId()));
      authTestSupport.deleteAccount(target.getId());
    }
  }

  @Test
  @DisplayName("Should reject a credential whose secret digest is not a SHA-256 digest")
  void shouldRejectCredentialWhoseSecretDigestIsNotSha256Digest() {
    var target = authTestSupport.createAccount();
    var shortDigest =
        pendingResetCodeRow(target, target, Instant.now().plus(Duration.ofHours(1)))
            .secretDigest(new byte[] {1, 2, 3})
            .build();

    try {
      assertThatThrownBy(() -> resetCodeRepository.saveAndFlush(shortDigest))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasStackTraceContaining("chk_password_reset_code_secret_digest_length");
    } finally {
      authTestSupport.deleteAccount(target.getId());
    }
  }

  @Test
  @DisplayName("Should reject a redeemed reset code that records no redemption time")
  void shouldRejectRedeemedResetCodeThatRecordsNoRedemptionTime() {
    var target = authTestSupport.createAccount();
    var redeemed =
        pendingResetCodeRow(target, target, Instant.now().plus(Duration.ofHours(1)))
            .status(PasswordResetCodeStatus.REDEEMED)
            .build();

    try {
      assertThatThrownBy(() -> resetCodeRepository.saveAndFlush(redeemed))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasStackTraceContaining("chk_password_reset_code_redeemed_at");
    } finally {
      // Reset codes cascade with their Account; a row that slipped through leaves with it.
      authTestSupport.deleteAccount(target.getId());
    }
  }

  private AccountInvitation savePendingInvitation(
      UserAccount target, UserAccount issuer, Instant expiresAt) {
    return invitationRepository.saveAndFlush(
        pendingInvitationRow(target, issuer, expiresAt).build());
  }

  private AccountInvitation.AccountInvitationBuilder<?, ?> pendingInvitationRow(
      UserAccount target, UserAccount issuer, Instant expiresAt) {
    var issued = opaqueCodes.issue();
    return AccountInvitation.builder()
        .recipientEmail("invitee-" + UUID.randomUUID() + "@example.com")
        .householdId(target.getHouseholdId())
        .householdName("Target Household")
        .householdRole(HouseholdRole.MEMBER)
        .profileName("Invitee")
        .profileKind(ProfileKind.ADULT)
        .issuerAccountId(issuer.getId())
        .expiresAt(expiresAt)
        .publicId(issued.publicId())
        .secretDigest(issued.digest());
  }

  private PasswordResetCode savePendingResetCode(
      UserAccount target, UserAccount issuer, Instant expiresAt) {
    return resetCodeRepository.saveAndFlush(pendingResetCodeRow(target, issuer, expiresAt).build());
  }

  private PasswordResetCode.PasswordResetCodeBuilder<?, ?> pendingResetCodeRow(
      UserAccount target, UserAccount issuer, Instant expiresAt) {
    var issued = opaqueCodes.issue();
    return PasswordResetCode.builder()
        .accountId(target.getId())
        .issuerAccountId(issuer.getId())
        .expiresAt(expiresAt)
        .publicId(issued.publicId())
        .secretDigest(issued.digest());
  }
}
