package com.streamarr.server.fakes;

import static com.streamarr.server.fixtures.PaginationFixture.firstSeek;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValueKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Fake Profile Manager Invitation Repository Tests")
class FakeProfileManagerInvitationRepositoryTest {

  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

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
  @DisplayName("Should order tied invitation IDs like PostgreSQL when pending rows are read")
  void shouldOrderTiedInvitationIdsLikePostgresWhenPendingRowsAreRead() {
    var now = Instant.parse("2026-08-21T12:00:00Z");
    var profileId = UUID.randomUUID();
    var lowerId = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
    var higherId = UUID.fromString("80000000-0000-0000-0000-000000000000");
    saveInvitation(higherId, profileId, now);
    saveInvitation(lowerId, profileId, now);

    var rows = fake.rowsPendingForProfile(profileId, now, firstSeek(NEWEST_FIRST, 2));

    assertThat(rows).extracting(row -> row.item().getId()).containsExactly(higherId, lowerId);
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
}
