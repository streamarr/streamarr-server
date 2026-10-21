package com.streamarr.server.repositories.auth;

import static com.streamarr.server.fixtures.PaginationFixture.firstSeek;
import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValueKind;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.security.WithAccountContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Profile Manager Invitation Repository Custom Implementation Integration Tests")
@WithAccountContext
class ProfileManagerInvitationRepositoryCustomImplIT extends AbstractIntegrationTest {

  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("Should read only live pending invitations when expired and accepted ones exist")
  void shouldReadOnlyLivePendingInvitationsWhenExpiredAndAcceptedOnesExist() {
    var inviter = authTestSupport.createAdminIdentity();
    var recipients =
        List.of(
            authTestSupport.createIdentity(),
            authTestSupport.createIdentity(),
            authTestSupport.createIdentity());
    var now = Instant.now();
    var live = saveInvitation(inviter, recipients.get(0), now.plusSeconds(3600));
    var expired = saveInvitation(inviter, recipients.get(1), now.minusSeconds(1));
    var accepted = saveInvitation(inviter, recipients.get(2), now.plusSeconds(3600));
    transactionTemplate.executeWithoutResult(
        _ -> assertThat(invitationRepository.tryAcceptPending(accepted.getId(), now)).isTrue());

    try {
      assertThat(
              invitationRepository.rowsPendingForProfile(
                  inviter.profile().getId(), now, firstSeek(NEWEST_FIRST, 10)))
          .extracting(row -> row.item().getId())
          .containsExactly(live.getId());
      assertThat(
              invitationRepository.rowsPendingForRecipient(
                  recipients.get(1).account().getId(), now, firstSeek(NEWEST_FIRST, 10)))
          .isEmpty();
      assertThat(
              invitationRepository.rowsPendingForRecipient(
                  recipients.get(2).account().getId(), now, firstSeek(NEWEST_FIRST, 10)))
          .isEmpty();
    } finally {
      invitationRepository.deleteAllById(List.of(live.getId(), expired.getId(), accepted.getId()));
      recipients.forEach(authTestSupport::deleteIdentity);
      authTestSupport.deleteIdentity(inviter);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("Should preserve an expired invitation when replacing it")
  void shouldPreserveExpiredInvitationWhenReplacingIt() {
    var inviter = authTestSupport.createAdminIdentity();
    var recipient = authTestSupport.createIdentity();
    var now = Instant.parse("2026-08-21T12:00:00Z");
    var expiredInvitation = saveInvitation(inviter, recipient, now.minusSeconds(1));

    try {
      transactionTemplate.executeWithoutResult(
          _ ->
              invitationRepository.invalidatePendingByProfileIdAndRecipientAccountId(
                  inviter.profile().getId(), recipient.account().getId(), "replaced", now));

      assertThat(invitationRepository.findById(expiredInvitation.getId()).orElseThrow())
          .satisfies(
              invitation -> {
                assertThat(invitation.getStatus())
                    .isEqualTo(ProfileManagerInvitationStatus.EXPIRED);
                assertThat(invitation.getDecidedAt()).isEqualTo(now);
                assertThat(invitation.getInvalidationReason()).isNull();
              });
    } finally {
      invitationRepository.deleteById(expiredInvitation.getId());
      authTestSupport.deleteIdentity(recipient);
      authTestSupport.deleteIdentity(inviter);
    }
  }

  @ParameterizedTest(name = "Should invalidate a pending invitation when its {0} disappears")
  @EnumSource(RequiredParty.class)
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("Should invalidate a pending invitation when its required party disappears")
  void shouldInvalidatePendingInvitationWhenRequiredPartyDisappears(RequiredParty requiredParty) {
    var inviter = authTestSupport.createAdminIdentity();
    var recipient = authTestSupport.createIdentity();
    var pendingInvitation = saveInvitation(inviter, recipient, Instant.now().plusSeconds(3600));

    try {
      clearRequiredParty(pendingInvitation.getId(), requiredParty);

      var resolved = invitationRepository.findById(pendingInvitation.getId()).orElseThrow();
      assertThat(resolved.getStatus()).isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
      assertThat(resolved.getDecidedAt()).isNotNull();
      assertThat(resolved.getInvalidationReason()).isEqualTo(requiredParty.reason());
      assertThat(requiredParty.idOf(resolved)).isNull();
    } finally {
      invitationRepository.deleteById(pendingInvitation.getId());
      authTestSupport.deleteIdentity(recipient);
      authTestSupport.deleteIdentity(inviter);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("Should clear the modifier when party deletion resolves a pending invitation")
  void shouldClearModifierWhenPartyDeletionResolvesAPendingInvitation() {
    var inviter = authTestSupport.createAdminIdentity();
    var recipient = authTestSupport.createIdentity();
    var pendingInvitation = saveInvitation(inviter, recipient, Instant.now().plusSeconds(3600));
    assertThat(pendingInvitation.getLastModifiedBy()).isNotNull();

    try {
      clearRequiredParty(pendingInvitation.getId(), RequiredParty.INVITER);

      var resolved = invitationRepository.findById(pendingInvitation.getId()).orElseThrow();
      assertThat(resolved.getStatus()).isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
      assertThat(resolved.getLastModifiedBy()).isNull();
    } finally {
      invitationRepository.deleteById(pendingInvitation.getId());
      authTestSupport.deleteIdentity(recipient);
      authTestSupport.deleteIdentity(inviter);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName("Should preserve expiry when a required invitation party disappears")
  void shouldPreserveExpiryWhenRequiredInvitationPartyDisappears() {
    var inviter = authTestSupport.createAdminIdentity();
    var recipient = authTestSupport.createIdentity();
    var expiredInvitation = saveInvitation(inviter, recipient, Instant.now().minusSeconds(1));

    try {
      clearRequiredParty(expiredInvitation.getId(), RequiredParty.PROFILE);

      assertThat(invitationRepository.findById(expiredInvitation.getId()).orElseThrow())
          .satisfies(
              invitation -> {
                assertThat(invitation.getStatus())
                    .isEqualTo(ProfileManagerInvitationStatus.EXPIRED);
                assertThat(invitation.getDecidedAt()).isNotNull();
                assertThat(invitation.getInvalidationReason()).isNull();
                assertThat(invitation.getProfileId()).isNull();
              });
    } finally {
      invitationRepository.deleteById(expiredInvitation.getId());
      authTestSupport.deleteIdentity(recipient);
      authTestSupport.deleteIdentity(inviter);
    }
  }

  private ProfileManagerInvitation saveInvitation(
      AuthTestSupport.TestIdentity inviter,
      AuthTestSupport.TestIdentity recipient,
      Instant expiresAt) {
    return invitationRepository.saveAndFlush(
        ProfileManagerInvitation.builder()
            .profileId(inviter.profile().getId())
            .profileName(inviter.profile().getName())
            .inviterAccountId(inviter.account().getId())
            .inviterDisplayName(inviter.account().getDisplayName())
            .recipientAccountId(recipient.account().getId())
            .recipientEmail(recipient.account().getEmail())
            .status(ProfileManagerInvitationStatus.PENDING)
            .expiresAt(expiresAt)
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[] {1})
            .build());
  }

  private void clearRequiredParty(UUID invitationId, RequiredParty requiredParty) {
    transactionTemplate.executeWithoutResult(
        _ -> clearRequiredPartyInTransaction(invitationId, requiredParty));
  }

  private void clearRequiredPartyInTransaction(UUID invitationId, RequiredParty requiredParty) {
    var update = dsl.update(PROFILE_MANAGER_INVITATION);
    switch (requiredParty) {
      case PROFILE ->
          update
              .set(PROFILE_MANAGER_INVITATION.PROFILE_ID, (UUID) null)
              .where(PROFILE_MANAGER_INVITATION.ID.eq(invitationId))
              .execute();
      case INVITER ->
          update
              .set(PROFILE_MANAGER_INVITATION.INVITER_ACCOUNT_ID, (UUID) null)
              .where(PROFILE_MANAGER_INVITATION.ID.eq(invitationId))
              .execute();
      case RECIPIENT ->
          update
              .set(PROFILE_MANAGER_INVITATION.RECIPIENT_ACCOUNT_ID, (UUID) null)
              .where(PROFILE_MANAGER_INVITATION.ID.eq(invitationId))
              .execute();
    }
  }

  private enum RequiredParty {
    PROFILE("Profile deleted") {
      @Override
      UUID idOf(ProfileManagerInvitation invitation) {
        return invitation.getProfileId();
      }
    },
    INVITER("inviter deleted") {
      @Override
      UUID idOf(ProfileManagerInvitation invitation) {
        return invitation.getInviterAccountId();
      }
    },
    RECIPIENT("recipient deleted") {
      @Override
      UUID idOf(ProfileManagerInvitation invitation) {
        return invitation.getRecipientAccountId();
      }
    };

    private final String reason;

    RequiredParty(String reason) {
      this.reason = reason;
    }

    String reason() {
      return reason;
    }

    abstract UUID idOf(ProfileManagerInvitation invitation);
  }
}
