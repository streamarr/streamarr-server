package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.security.WithAccountContext;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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

@Tag("IntegrationTest")
@DisplayName("Profile Manager Invitation Repository Custom Implementation Integration Tests")
@WithAccountContext
class ProfileManagerInvitationRepositoryCustomImplIT extends AbstractIntegrationTest {

  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DSLContext dsl;

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName(
      "Should return the newest pending Profile invitation window when lookahead is requested")
  void shouldReturnNewestPendingProfileInvitationWindowWhenLookaheadIsRequested() {
    var inviter = authTestSupport.createAdminIdentity();
    var recipients =
        List.of(
            authTestSupport.createIdentity(),
            authTestSupport.createIdentity(),
            authTestSupport.createIdentity());
    var expiresAt = Instant.parse("2026-08-21T13:00:00Z");
    var invitations =
        recipients.stream()
            .map(recipient -> saveInvitation(inviter, recipient, expiresAt))
            .toList();
    var createdAt = Instant.parse("2026-08-21T12:00:00Z");
    for (var index = 0; index < invitations.size(); index++) {
      dsl.update(PROFILE_MANAGER_INVITATION)
          .set(
              PROFILE_MANAGER_INVITATION.CREATED_ON,
              createdAt.plusSeconds(index).atOffset(ZoneOffset.UTC))
          .where(PROFILE_MANAGER_INVITATION.ID.eq(invitations.get(index).getId()))
          .execute();
    }

    try {
      var found =
          invitationRepository.findPendingByProfileId(
              inviter.profile().getId(), createdAt, forwardOptions(2));

      assertThat(found)
          .extracting(ProfileManagerInvitation::getId)
          .containsExactly(
              invitations.get(2).getId(),
              invitations.get(1).getId(),
              invitations.getFirst().getId());
    } finally {
      invitationRepository.deleteAllById(
          invitations.stream().map(ProfileManagerInvitation::getId).toList());
      recipients.forEach(authTestSupport::deleteIdentity);
      authTestSupport.deleteIdentity(inviter);
    }
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  @DisplayName(
      "Should return the newest pending recipient invitation window when lookahead is requested")
  void shouldReturnNewestPendingRecipientInvitationWindowWhenLookaheadIsRequested() {
    var inviters =
        List.of(
            authTestSupport.createAdminIdentity(),
            authTestSupport.createAdminIdentity(),
            authTestSupport.createAdminIdentity());
    var recipient = authTestSupport.createIdentity();
    var expiresAt = Instant.parse("2026-08-21T13:00:00Z");
    var invitations =
        inviters.stream().map(inviter -> saveInvitation(inviter, recipient, expiresAt)).toList();
    var createdAt = Instant.parse("2026-08-21T12:00:00Z");
    for (var index = 0; index < invitations.size(); index++) {
      dsl.update(PROFILE_MANAGER_INVITATION)
          .set(
              PROFILE_MANAGER_INVITATION.CREATED_ON,
              createdAt.plusSeconds(index).atOffset(ZoneOffset.UTC))
          .where(PROFILE_MANAGER_INVITATION.ID.eq(invitations.get(index).getId()))
          .execute();
    }

    try {
      var found =
          invitationRepository.findPendingByRecipientAccountId(
              recipient.account().getId(), createdAt, forwardOptions(2));

      assertThat(found)
          .extracting(ProfileManagerInvitation::getId)
          .containsExactly(
              invitations.get(2).getId(),
              invitations.get(1).getId(),
              invitations.getFirst().getId());
    } finally {
      invitationRepository.deleteAllById(
          invitations.stream().map(ProfileManagerInvitation::getId).toList());
      authTestSupport.deleteIdentity(recipient);
      inviters.forEach(authTestSupport::deleteIdentity);
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
      invitationRepository.invalidatePendingByProfileIdAndRecipientAccountId(
          inviter.profile().getId(), recipient.account().getId(), "replaced", now);

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

  private KeysetPaginationOptions forwardOptions(int limit) {
    return new KeysetPaginationOptions(
        null,
        PaginationOptions.builder()
            .paginationDirection(PaginationDirection.FORWARD)
            .cursor(Optional.empty())
            .limit(limit)
            .build());
  }

  private void clearRequiredParty(UUID invitationId, RequiredParty requiredParty) {
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
