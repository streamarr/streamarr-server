package com.streamarr.server.fakes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Fake Watch History Repository Tests")
class FakeWatchHistoryRepositoryTest {

  private final FakeWatchHistoryRepository watchHistoryRepository =
      new FakeWatchHistoryRepository();

  @Test
  @DisplayName("Should save one manual mark when one batch names the same title twice")
  void shouldSaveOneManualMarkWhenOneBatchNamesTheSameTitleTwice() {
    var profileId = UUID.randomUUID();
    var collectableId = UUID.randomUUID();

    var marked =
        watchHistoryRepository.batchInsert(profileId, List.of(collectableId, collectableId), 0);

    assertThat(marked).isEqualTo(1);
    assertThat(
            watchHistoryRepository.findByProfileIdAndCollectableIdIn(
                profileId, List.of(collectableId)))
        .hasSize(1);
  }
}
