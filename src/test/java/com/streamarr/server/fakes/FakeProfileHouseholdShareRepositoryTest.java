package com.streamarr.server.fakes;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Fake Profile Household Share Repository Tests")
class FakeProfileHouseholdShareRepositoryTest {

  private static final UUID LOW = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID HIGH = UUID.fromString("80000000-0000-0000-0000-000000000001");

  private final FakeProfileHouseholdShareRepository fake =
      new FakeProfileHouseholdShareRepository();

  /**
   * S9 — RED today: UUID.compareTo orders by signed 64-bit halves, so HIGH (negative high half)
   * sorts first; PostgreSQL orders uuid byte-wise, so LOW comes first — page boundaries diverge.
   */
  @Test
  @DisplayName(
      "Should order a page by PostgreSQL uuid byte order when ids straddle the signed boundary")
  void shouldOrderPageByPostgresUuidByteOrderWhenIdsStraddleSignedBoundary() {
    var householdId = UUID.randomUUID();
    for (var id : List.of(HIGH, LOW)) {
      var share =
          ProfileHouseholdShare.builder()
              .profileId(UUID.randomUUID())
              .householdId(householdId)
              .status(ProfileShareStatus.PENDING)
              .build();
      share.setId(id);
      fake.save(share);
    }

    var page = fake.findPendingOffersPage(householdId, Instant.now(), firstPage(10));

    assertThat(page).extracting(ProfileHouseholdShare::getId).containsExactly(LOW, HIGH);
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
}
