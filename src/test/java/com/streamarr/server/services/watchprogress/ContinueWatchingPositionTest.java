package com.streamarr.server.services.watchprogress;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.WatchStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Continue Watching Position Tests")
class ContinueWatchingPositionTest {

  @ParameterizedTest
  @EnumSource(
      value = WatchStatus.class,
      names = {"UNWATCHED", "WATCHED"})
  @DisplayName("Should omit placement when the item has no resume position")
  void shouldOmitPlacementWhenTheItemHasNoResumePosition(WatchStatus status) {
    var position = ContinueWatchingPosition.from(UUID.randomUUID(), status, null);

    assertThat(position.matchesFilter()).isFalse();
    assertThat(position.orderKey()).isNull();
  }

  @Test
  @DisplayName("Should order by the full instant then descending UUID when items are in progress")
  void shouldOrderByTheFullInstantThenDescendingUuidWhenItemsAreInProgress() {
    var lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    var higherId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    var earlier = Instant.parse("2026-10-19T10:00:00.123100Z");
    var later = Instant.parse("2026-10-19T10:00:00.123900Z");
    var newer = ContinueWatchingPosition.from(lowerId, WatchStatus.IN_PROGRESS, later);
    var older = ContinueWatchingPosition.from(higherId, WatchStatus.IN_PROGRESS, earlier);
    var tied = ContinueWatchingPosition.from(higherId, WatchStatus.IN_PROGRESS, later);

    assertThat(newer.matchesFilter()).isTrue();
    assertThat(
            List.of(older, newer, tied).stream()
                .sorted(Comparator.comparing(ContinueWatchingPosition::orderKey)))
        .containsExactly(tied, newer, older);
  }
}
