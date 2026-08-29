package com.streamarr.server.fakes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.exceptions.InvalidPaginationCursorException;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Fake Profile Manager Invitation Repository Tests")
class FakeProfileManagerInvitationRepositoryTest {

  private final FakeProfileManagerInvitationRepository fake =
      new FakeProfileManagerInvitationRepository();

  @Test
  @DisplayName("Should preserve expiry when replacing an invitation")
  void shouldPreserveExpiryWhenReplacingInvitation() {
    var now = Instant.parse("2026-08-21T12:00:00Z");
    var profileId = UUID.randomUUID();
    var recipientAccountId = UUID.randomUUID();
    var expiredInvitation =
        fake.save(
            ProfileManagerInvitation.builder()
                .profileId(profileId)
                .profileName("Kids")
                .inviterAccountId(UUID.randomUUID())
                .inviterDisplayName("Inviter")
                .recipientAccountId(recipientAccountId)
                .recipientEmail("recipient@example.com")
                .status(ProfileManagerInvitationStatus.PENDING)
                .expiresAt(now.minusSeconds(1))
                .publicId(UUID.randomUUID().toString())
                .secretDigest(new byte[] {1})
                .build());

    fake.invalidatePendingByProfileIdAndRecipientAccountId(
        profileId, recipientAccountId, "replaced", now);

    assertThat(expiredInvitation)
        .satisfies(
            invitation -> {
              assertThat(invitation.getStatus()).isEqualTo(ProfileManagerInvitationStatus.EXPIRED);
              assertThat(invitation.getDecidedAt()).isEqualTo(now);
              assertThat(invitation.getInvalidationReason()).isNull();
            });
  }

  @Test
  @DisplayName("Should reject an unknown invitation cursor when an invitation page is requested")
  void shouldRejectUnknownInvitationCursorWhenInvitationPageIsRequested() {
    var now = Instant.parse("2026-08-21T12:00:00Z");
    var profileId = UUID.randomUUID();
    fake.save(
        ProfileManagerInvitation.builder()
            .profileId(profileId)
            .profileName("Kids")
            .inviterAccountId(UUID.randomUUID())
            .inviterDisplayName("Inviter")
            .recipientAccountId(UUID.randomUUID())
            .recipientEmail("recipient@example.com")
            .status(ProfileManagerInvitationStatus.PENDING)
            .expiresAt(now.plusSeconds(1))
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[] {1})
            .build());
    var options =
        new KeysetPaginationOptions(
            UUID.randomUUID(),
            PaginationOptions.builder()
                .paginationDirection(PaginationDirection.FORWARD)
                .cursor(Optional.of("unknown"))
                .limit(10)
                .build());

    assertThatThrownBy(() -> fake.findPendingByProfileId(profileId, now, options))
        .isInstanceOf(InvalidPaginationCursorException.class);
  }

  @Test
  @DisplayName("Should include the cursor row when a forward invitation window is requested")
  void shouldIncludeCursorRowWhenForwardInvitationWindowIsRequested() {
    var now = Instant.parse("2026-08-21T12:00:00Z");
    var profileId = UUID.randomUUID();
    var invitations = invitations(now, profileId);
    var options = cursorWindow(invitations.get(2).getId(), PaginationDirection.FORWARD);

    var window = fake.findPendingByProfileId(profileId, now, options);

    assertThat(window)
        .extracting(ProfileManagerInvitation::getId)
        .containsExactly(
            invitations.get(2).getId(), invitations.get(1).getId(), invitations.getFirst().getId());
  }

  @Test
  @DisplayName("Should include the cursor row when a reverse invitation window is requested")
  void shouldIncludeCursorRowWhenReverseInvitationWindowIsRequested() {
    var now = Instant.parse("2026-08-21T12:00:00Z");
    var profileId = UUID.randomUUID();
    var invitations = invitations(now, profileId);
    var options = cursorWindow(invitations.get(1).getId(), PaginationDirection.REVERSE);

    var window = fake.findPendingByProfileId(profileId, now, options);

    assertThat(window)
        .extracting(ProfileManagerInvitation::getId)
        .containsExactly(
            invitations.get(3).getId(), invitations.get(2).getId(), invitations.get(1).getId());
  }

  @Test
  @DisplayName("Should order tied invitation IDs like PostgreSQL when a page is requested")
  void shouldOrderTiedInvitationIdsLikePostgresWhenPageIsRequested() {
    var now = Instant.parse("2026-08-21T12:00:00Z");
    var profileId = UUID.randomUUID();
    var lowerId = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
    var higherId = UUID.fromString("80000000-0000-0000-0000-000000000000");
    saveInvitation(higherId, profileId, now);
    saveInvitation(lowerId, profileId, now);

    var window = fake.findPendingByProfileId(profileId, now, firstPage(2));

    assertThat(window)
        .extracting(ProfileManagerInvitation::getId)
        .containsExactly(lowerId, higherId);
  }

  private List<ProfileManagerInvitation> invitations(Instant now, UUID profileId) {
    return IntStream.range(0, 4)
        .mapToObj(
            index -> {
              var invitation =
                  ProfileManagerInvitation.builder()
                      .profileId(profileId)
                      .profileName("Kids")
                      .inviterAccountId(UUID.randomUUID())
                      .inviterDisplayName("Inviter")
                      .recipientAccountId(UUID.randomUUID())
                      .recipientEmail("recipient@example.com")
                      .status(ProfileManagerInvitationStatus.PENDING)
                      .expiresAt(now.plusSeconds(3600))
                      .publicId(UUID.randomUUID().toString())
                      .secretDigest(new byte[] {1})
                      .build();
              AuditFieldSetter.setCreatedOn(invitation, now.plusSeconds(index));
              return fake.save(invitation);
            })
        .toList();
  }

  private void saveInvitation(UUID invitationId, UUID profileId, Instant now) {
    var invitation =
        ProfileManagerInvitation.builder()
            .id(invitationId)
            .profileId(profileId)
            .profileName("Kids")
            .inviterAccountId(UUID.randomUUID())
            .inviterDisplayName("Inviter")
            .recipientAccountId(UUID.randomUUID())
            .recipientEmail("recipient@example.com")
            .status(ProfileManagerInvitationStatus.PENDING)
            .expiresAt(now.plusSeconds(3600))
            .publicId(UUID.randomUUID().toString())
            .secretDigest(new byte[] {1})
            .build();
    AuditFieldSetter.setCreatedOn(invitation, now);
    fake.save(invitation);
  }

  private static KeysetPaginationOptions firstPage(int limit) {
    return new KeysetPaginationOptions(
        null,
        PaginationOptions.builder()
            .paginationDirection(PaginationDirection.FORWARD)
            .cursor(Optional.empty())
            .limit(limit)
            .build());
  }

  private static KeysetPaginationOptions cursorWindow(
      UUID cursorId, PaginationDirection direction) {
    return new KeysetPaginationOptions(
        cursorId,
        PaginationOptions.builder()
            .paginationDirection(direction)
            .cursor(Optional.of("cursor"))
            .limit(1)
            .build());
  }
}
