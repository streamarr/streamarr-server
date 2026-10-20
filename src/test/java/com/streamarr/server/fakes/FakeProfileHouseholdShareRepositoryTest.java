package com.streamarr.server.fakes;

import static com.streamarr.server.fixtures.PaginationFixture.firstSeek;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValueKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Fake Profile Household Share Repository Tests")
class FakeProfileHouseholdShareRepositoryTest {

  private static final UUID LOW = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID HIGH = UUID.fromString("80000000-0000-0000-0000-000000000001");
  private static final Instant CREATED_ON = Instant.parse("2026-08-21T12:00:00Z");
  private static final ListOrder OLDEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.ASC).build();

  private final FakeProfileHouseholdShareRepository fake =
      new FakeProfileHouseholdShareRepository();

  // Java UUID.compareTo uses signed halves. PostgreSQL compares UUIDs byte by byte, so these
  // values must sort LOW before HIGH.
  @Test
  @DisplayName(
      "Should order tied offers by PostgreSQL uuid byte order when ids straddle the signed boundary")
  void shouldOrderTiedOffersByPostgresUuidByteOrderWhenIdsStraddleSignedBoundary() {
    var householdId = UUID.randomUUID();
    for (var id : List.of(HIGH, LOW)) {
      var share =
          ProfileHouseholdShare.builder()
              .profileId(UUID.randomUUID())
              .householdId(householdId)
              .status(ProfileShareStatus.PENDING)
              .build();
      share.setId(id);
      AuditFieldSetter.setCreatedOn(fake.save(share), CREATED_ON);
    }

    var rows = fake.rowsPendingInHousehold(householdId, Instant.now(), firstSeek(OLDEST_FIRST, 10));

    assertThat(rows).extracting(row -> row.item().getId()).containsExactly(LOW, HIGH);
  }

  @Test
  @DisplayName("Should preserve expiry when invalidating offers from a former manager")
  void shouldPreserveExpiryWhenInvalidatingOffersFromFormerManager() {
    var now = Instant.parse("2026-08-21T12:00:00Z");
    var profileId = UUID.randomUUID();
    var offererAccountId = UUID.randomUUID();
    var expiredOffer =
        fake.save(
            ProfileHouseholdShare.builder()
                .profileId(profileId)
                .householdId(UUID.randomUUID())
                .status(ProfileShareStatus.PENDING)
                .offeredByAccountId(offererAccountId)
                .expiresAt(now.minusSeconds(1))
                .build());

    fake.invalidatePendingOffersByProfileIdAndOffererAccountId(
        profileId, offererAccountId, "offerer left", now);

    assertThat(expiredOffer)
        .satisfies(
            offer -> {
              assertThat(offer.getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
              assertThat(offer.getDecidedAt()).isEqualTo(now);
              assertThat(offer.getLastModifiedOn()).isEqualTo(now);
              assertThat(offer.getInvalidationReason()).isEmpty();
            });
  }
}
