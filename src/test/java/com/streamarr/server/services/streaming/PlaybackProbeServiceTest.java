package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.MediaFileStreamInfo;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.services.events.library.MediaFileProbeRequested;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.probe.PersistedProbeReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Playback probe service")
class PlaybackProbeServiceTest {

  @ParameterizedTest
  @EnumSource(ProbeError.class)
  @DisplayName("Should retain the terminal failure when playback has a failed probe outcome")
  void shouldRetainTheTerminalFailureWhenPlaybackHasAFailedProbeOutcome(ProbeError reason) {
    var mediaFileId = UUID.randomUUID();
    var events = new CapturingEventPublisher();
    var row =
        MediaFileContainerInfo.builder()
            .mediaFileId(mediaFileId)
            .snapshot(new SourceFileSnapshot(1000, Instant.EPOCH))
            .probeVersion(ProbeVersion.CURRENT)
            .probeError(reason)
            .build();
    var service = new PlaybackProbeService(new PersistedProbeReader(storing(row)), events);

    assertThat(service.read(mediaFileId))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.ProbeFailed(reason)));
    assertThat(events.getEventsOfType(MediaFileProbeRequested.class)).isEmpty();
  }

  @Test
  @DisplayName("Should request background work when playback has no persisted probe outcome")
  void shouldRequestBackgroundWorkWhenPlaybackHasNoPersistedProbeOutcome() {
    var mediaFileId = UUID.randomUUID();
    var events = new CapturingEventPublisher();
    var service =
        new PlaybackProbeService(
            new PersistedProbeReader(new FakeMediaFileContainerInfoRepository()), events);

    assertThat(service.read(mediaFileId))
        .isEqualTo(Outcome.rejected(new CreateStreamSessionRejection.ProbeNotReady()));
    assertThat(events.getEventsOfType(MediaFileProbeRequested.class))
        .containsExactly(new MediaFileProbeRequested(mediaFileId));
  }

  @Test
  @DisplayName("Should propagate the scheduling failure when a probe request cannot be enqueued")
  void shouldPropagateTheSchedulingFailureWhenAProbeRequestCannotBeEnqueued() {
    var mediaFileId = UUID.randomUUID();
    var schedulingFailure = new IllegalStateException("Probe task insert failed");
    var service =
        new PlaybackProbeService(
            new PersistedProbeReader(new FakeMediaFileContainerInfoRepository()),
            _ -> {
              throw schedulingFailure;
            });

    assertThatThrownBy(() -> service.read(mediaFileId)).isSameAs(schedulingFailure);
  }

  @Test
  @DisplayName("Should supply persisted properties when playback has a successful probe outcome")
  void shouldSupplyPersistedPropertiesWhenPlaybackHasASuccessfulProbeOutcome() {
    var mediaFileId = UUID.randomUUID();
    var events = new CapturingEventPublisher();
    var row =
        MediaFileContainerInfo.builder()
            .mediaFileId(mediaFileId)
            .snapshot(new SourceFileSnapshot(1000, Instant.EPOCH))
            .probeVersion(ProbeVersion.CURRENT)
            .container(
                ProbeContainer.builder()
                    .duration(Optional.of(Duration.ofSeconds(73)))
                    .bitrate(OptionalLong.of(4_000_000))
                    .build())
            .streams(
                List.of(
                    MediaFileStreamInfo.builder().stream(
                            mediaFileId,
                            StreamInfo.builder()
                                .index(0)
                                .codecType("video")
                                .codec(Optional.of("h264"))
                                .width(OptionalInt.of(1920))
                                .height(OptionalInt.of(1080))
                                .build())
                        .build()))
            .build();
    var service = new PlaybackProbeService(new PersistedProbeReader(storing(row)), events);

    var probe =
        service
            .read(mediaFileId)
            .fold(
                value -> value,
                rejections -> {
                  throw new AssertionError("Expected playback readiness: " + rejections);
                });

    assertThat(probe.duration()).isEqualTo(Duration.ofSeconds(73));
    assertThat(probe.bitrate()).isEqualTo(4_000_000);
    assertThat(probe.videoCodec()).isEqualTo("h264");
    assertThat(probe.width()).isEqualTo(1920);
    assertThat(probe.height()).isEqualTo(1080);
    assertThat(events.getEventsOfType(MediaFileProbeRequested.class)).isEmpty();
  }

  private static FakeMediaFileContainerInfoRepository storing(MediaFileContainerInfo row) {
    var repository = new FakeMediaFileContainerInfoRepository();
    repository.store(row);
    return repository;
  }
}
