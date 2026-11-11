package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.createStreamSessionCommandBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamMeasurementFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.events.library.UnmeasuredCopyStartedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Unmeasured Copy Start Tests")
class UnmeasuredCopyStartTest {

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeTranscodeExecutor transcodeExecutor = new FakeTranscodeExecutor();
  private final FakeMediaFileContainerInfoRepository probeResults =
      new FakeMediaFileContainerInfoRepository();
  private final CapturingEventPublisher events = new CapturingEventPublisher();
  private HlsStreamingService service;
  private MediaFile file;

  @BeforeEach
  void setUp() {
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();
    var segmentStore = new FakeSegmentStore();
    var runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    var meters = new SimpleMeterRegistry();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .heldRequests(transcodeExecutor.heldSegmentRequests())
            .eventPublisher(events)
            .build();
    service =
        HlsStreamingService.builder()
            .mediaFileRepository(mediaFiles)
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .playbackProbeService(playbackProbeServiceBuilder(probeResults).build())
            .transcodeDecisionService(new TranscodeDecisionService(properties, meters))
            .qualityLadderService(new QualityLadderService())
            .properties(properties)
            .authorityGate(new FakePlaybackAuthorityGate())
            .runtimeRegistry(runtimeRegistry)
            .producerLifecycle(rig.lifecycle())
            .deliveryCoordinator(rig.coordinator())
            .meterRegistry(meters)
            .languagePreferences((_, _) -> List.of())
            .build();
    probeResults.setDefaultProbe(defaultProbeBuilder().build());
    file =
        mediaFiles.save(
            MediaFile.builder()
                .filepathUri("file:///media/movies/test.mkv")
                .filename("test.mkv")
                .status(MediaFileStatus.MATCHED)
                .size(1_000_000L)
                .build());
  }

  @Test
  @DisplayName(
      "Should announce the unmeasured copy of the file when a stream session copies a file without"
          + " a measurement")
  void shouldAnnounceTheUnmeasuredCopyOfTheFileWhenAStreamSessionCopiesAFileWithoutAMeasurement() {
    var session = accepted(service.createSession(command().build()));

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(unmeasuredCopies()).containsExactly(new UnmeasuredCopyStartedEvent(file.getId()));
  }

  @Test
  @DisplayName("Should announce no unmeasured copy when the stream session encodes the video")
  void shouldAnnounceNoUnmeasuredCopyWhenTheStreamSessionEncodesTheVideo() {
    var session =
        accepted(
            service.createSession(
                command().videoFormatConstraint(VideoFormatConstraint.ENCODE_ONLY).build()));

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(unmeasuredCopies()).isEmpty();
  }

  @Test
  @DisplayName("Should announce no unmeasured copy when the copied file has a measurement")
  void shouldAnnounceNoUnmeasuredCopyWhenTheCopiedFileHasAMeasurement() {
    // 2,700,000,000 bytes over the file's 7,200 s: 3 Mbit/s.
    var measurement =
        new StreamMeasurements(
            StreamMeasurementSource.BITRATE_SCAN,
            List.of(
                StreamMeasurementFixture.measuredStreamBuilder(0, 2_700_000_000L)
                    .measuredDuration(7_200_000)
                    .build()));
    probeResults.setDefaultOutcome(
        ProbeFixture.completeProbeBuilder(defaultProbeBuilder().build())
            .streamMeasurements(Optional.of(measurement))
            .build());

    var session = accepted(service.createSession(command().build()));

    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(unmeasuredCopies()).isEmpty();
  }

  @Test
  @DisplayName("Should announce no unmeasured copy when the copy's job attempt cannot start")
  void shouldAnnounceNoUnmeasuredCopyWhenTheCopysJobAttemptCannotStart() {
    transcodeExecutor.failUntargetedStarts();
    var command = command().build();

    assertThatThrownBy(() -> service.createSession(command)).isInstanceOf(TranscodeException.class);
    assertThat(unmeasuredCopies()).isEmpty();
  }

  private CreateStreamSessionCommand.CreateStreamSessionCommandBuilder command() {
    return createStreamSessionCommandBuilder(file.getId(), UUID.randomUUID());
  }

  private List<UnmeasuredCopyStartedEvent> unmeasuredCopies() {
    return events.getEventsOfType(UnmeasuredCopyStartedEvent.class);
  }
}
