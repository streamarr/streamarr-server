package com.streamarr.server.fakes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.exceptions.InvalidPaginationCursorException;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
}
