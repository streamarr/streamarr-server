package com.streamarr.server.graphql.dataloaders;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Watch Progress DataLoader Tests")
class SessionProgressDataLoaderTest {

  private FakeSessionProgressRepository sessionProgressRepository;
  private SessionProgressDataLoader dataLoader;

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @BeforeEach
  void setUp() {
    sessionProgressRepository = new FakeSessionProgressRepository();
    var service =
        new WatchStatusService(
            sessionProgressRepository,
            new FakeMediaFileRepository(),
            new FakeEpisodeRepository(),
            new FakeSeasonRepository());
    dataLoader = new SessionProgressDataLoader(service);
  }

  @Test
  @DisplayName("Should return progress for each media file ID when batch loading")
  void shouldReturnProgressForEachMediaFileIdWhenBatchLoading() throws Exception {
    var mediaFileId1 = UUID.randomUUID();
    var mediaFileId2 = UUID.randomUUID();
    saveProgress(mediaFileId1, 300, 50.0, 600);
    saveProgress(mediaFileId2, 600, 75.0, 800);

    var result = dataLoader.load(Set.of(mediaFileId1, mediaFileId2)).toCompletableFuture().get();

    assertThat(result.get(mediaFileId1)).isNotNull();
    assertThat(result.get(mediaFileId1).positionSeconds()).isEqualTo(300);
    assertThat(result.get(mediaFileId1).percentComplete()).isEqualTo(50.0);
    assertThat(result.get(mediaFileId1).durationSeconds()).isEqualTo(600);
    assertThat(result.get(mediaFileId2)).isNotNull();
    assertThat(result.get(mediaFileId2).positionSeconds()).isEqualTo(600);
    assertThat(result.get(mediaFileId2).percentComplete()).isEqualTo(75.0);
    assertThat(result.get(mediaFileId2).durationSeconds()).isEqualTo(800);
  }

  @Test
  @DisplayName("Should return null when media file has no progress")
  void shouldReturnNullWhenMediaFileHasNoProgress() throws Exception {
    var unknownId = UUID.randomUUID();

    var result = dataLoader.load(Set.of(unknownId)).toCompletableFuture().get();

    assertThat(result).containsKey(unknownId);
    assertThat(result.get(unknownId)).isNull();
  }

  private void saveProgress(UUID mediaFileId, int position, double percent, int duration) {
    sessionProgressRepository.save(
        SessionProgress.builder()
            .sessionId(UUID.randomUUID())
            .userId(USER_ID)
            .mediaFileId(mediaFileId)
            .positionSeconds(position)
            .percentComplete(percent)
            .durationSeconds(duration)
            .build());
  }
}
