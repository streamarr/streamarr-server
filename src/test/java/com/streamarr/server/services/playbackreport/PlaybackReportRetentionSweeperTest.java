package com.streamarr.server.services.playbackreport;

import static com.streamarr.server.fixtures.PlayerFiguresFixture.unknownFiguresBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.FakePlaybackReportRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Playback Report Retention Sweeper Tests")
class PlaybackReportRetentionSweeperTest {

  private static final Instant NOW = Instant.parse("2026-11-04T20:00:00Z");

  private final MutableClock clock = new MutableClock(new AtomicReference<>(NOW));
  private final FakePlaybackReportRepository repository = new FakePlaybackReportRepository();
  private final PlaybackReportRetentionSweeper sweeper =
      new PlaybackReportRetentionSweeper(
          repository, new TransactionTemplate(new FakeTransactionManager()), clock);

  @Test
  @DisplayName("Should delete a report when 30 days have passed since it was shared")
  void shouldDeleteReportWhenThirtyDaysHavePassedSinceItWasShared() {
    var expired = shareAt(NOW.minus(Duration.ofDays(30)).minusSeconds(1));
    var kept = shareAt(NOW.minus(Duration.ofDays(30)).plusSeconds(1));

    sweeper.deleteExpiredReports();

    assertThat(repository.storedReports())
        .extracting(PlaybackReport::id)
        .containsExactly(kept)
        .doesNotContain(expired);
  }

  @Test
  @DisplayName("Should delete the reports as the System actor when the sweep runs")
  void shouldDeleteReportsAsSystemActorWhenSweepRuns() {
    sweeper.deleteExpiredReports();

    assertThat(repository.deletionsRanAsSystemActor()).containsExactly(true);
  }

  private UUID shareAt(Instant sharedAt) {
    return repository.save(
        NewPlaybackReport.builder()
            .sharedAt(sharedAt)
            .profileId(UUID.randomUUID())
            .playbackSessionId(UUID.randomUUID())
            .streamSessionId(UUID.randomUUID())
            .mediaFileId(UUID.randomUUID())
            .note(Optional.empty())
            .requestedChoice(VideoQuality.AUTO)
            .playbackChoice(VideoQuality.AUTO)
            .decisions(List.of())
            .player(unknownFiguresBuilder().build())
            .build());
  }
}
