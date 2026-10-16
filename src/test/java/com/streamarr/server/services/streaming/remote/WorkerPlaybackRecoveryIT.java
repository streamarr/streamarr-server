package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.PlaybackFormatAttempt;
import com.streamarr.server.services.streaming.PlaybackSessionRequest;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import com.streamarr.server.services.streaming.PlaybackSessionSnapshot;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.SegmentDelivery;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.StartPlaybackSessionCommand;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRegistry;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

@Tag("IntegrationTest")
@DisplayName("Worker Playback Recovery Integration Tests")
class WorkerPlaybackRecoveryIT {

  private static final UUID SOURCE_NAMESPACE = UUID.randomUUID();

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "Should replace a rejected copy with a matching encoded playlist when the source changes after probing")
  void shouldReplaceARejectedCopyWithAMatchingEncodedPlaylistWhenTheSourceChangesAfterProbing()
      throws Exception {
    var sourceRoot = Files.createDirectory(tempDir.resolve("media"));
    var segments = new LocalSegmentStore(tempDir.resolve("segments"));
    var mismatches = new LinkedBlockingQueue<VideoCopyOutputMismatchEvent>();
    var listeners = new CopyOnWriteArrayList<Consumer<VideoCopyOutputMismatchEvent>>();
    ApplicationEventPublisher events =
        event -> {
          if (event instanceof VideoCopyOutputMismatchEvent mismatch) {
            mismatches.add(mismatch);
            listeners.forEach(listener -> listener.accept(mismatch));
          }
        };

    try (var server =
            WorkerSessionServer.builder()
                .configuration(serverConfigurationBuilder().build())
                .eventPublisher(events)
                .segmentStore(segments)
                .meterRegistry(new SimpleMeterRegistry())
                .build();
        var worker =
            WorkerContainerFixture.builder()
                .workerSessions(server)
                .sourceNamespaceId(SOURCE_NAMESPACE)
                .sourceRoot(sourceRoot)
                .availableSlots(2)
                .build()) {
      server.start();
      worker.start();
      var source = worker.generateMedia(sourceRoot.resolve("movie.mp4"), sourceArguments("3.1"));
      try (var playback = new PlaybackRig(server, segments, source)) {
        listeners.add(playback.sessions::onVideoCopyOutputMismatch);
        worker.generateMedia(source, sourceArguments("3.2"));

        var initial = accepted(playback.sessions.start(playback.command));
        var mismatch = mismatches.poll(15, TimeUnit.SECONDS);
        assertThat(mismatch).as("the real worker rejects the stale copy codec string").isNotNull();
        assertThat(worker.commandFor(mismatch.jobAttemptId()).orElseThrow())
            .containsSubsequence("-c:v", "copy");
        var replacement = playback.awaitReplacement(initial);

        assertThat(replacement.getSessionId()).isNotEqualTo(mismatch.streamSessionId());
        assertThat(replacement.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
        assertThat(playback.streams.getAllSessions()).containsExactly(replacement);
        assertThat(segments.segmentExists(mismatch.streamSessionId(), "init.mp4")).isFalse();
        var variant = replacement.getVariants().getFirst().label();
        var initialization = playback.deliver(replacement, variant, "init.mp4");
        var media = playback.deliver(replacement, variant, "segment0.m4s");
        var playlist = playback.playlists.generateMultivariantPlaylist(replacement, "test-token");

        assertThat(playlist)
            .contains("CODECS=\"" + Fmp4Fixture.avcCodecString(initialization) + "\"");
        var served = Files.write(sourceRoot.resolve("served.mp4"), combine(initialization, media));
        assertThat(worker.decodedVideoFrameCount(served)).isPositive();
        assertThat(mismatches).isEmpty();
      }
    } finally {
      segments.shutdown();
    }
  }

  private static List<String> sourceArguments(String level) {
    return List.of(
        "-f",
        "lavfi",
        "-i",
        "testsrc2=size=320x180:rate=24",
        "-t",
        "2",
        "-c:v",
        "libx264",
        "-profile:v",
        "high",
        "-level:v",
        level,
        "-pix_fmt",
        "yuv420p",
        "-g",
        "24",
        "-color_primaries",
        "bt709",
        "-color_trc",
        "bt709",
        "-colorspace",
        "bt709",
        "-color_range",
        "tv",
        "-an");
  }

  private static byte[] combine(byte[] initialization, byte[] media) {
    return ByteBuffer.allocate(initialization.length + media.length)
        .put(initialization)
        .put(media)
        .array();
  }

  private static final class PlaybackRig implements AutoCloseable {
    private final HlsStreamingService streams;
    private final PlaybackSessionService sessions;
    private final StartPlaybackSessionCommand command;
    private final SegmentDeliveryCoordinator delivery;
    private final HlsPlaylistService playlists;

    private PlaybackRig(WorkerSessionServer server, LocalSegmentStore segments, Path source) {
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE, source.getParent());
      var outcome =
          new RemoteFfprobeService(server, SOURCE_NAMESPACE, source.getParent())
              .probe(
                  ProbeExecutionRequest.builder()
                      .sourcePath(source)
                      .attemptId(UUID.randomUUID())
                      .probeVersion(ProbeVersion.CURRENT)
                      .build());
      assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
      var probe = (ProbeOutcome.Success) outcome;
      assertThat(probe.mediaProbe().videoPlaybackFacts().orElseThrow().codecString())
          .contains("avc1.64001F");
      var probes = new FakeMediaFileContainerInfoRepository();
      probes.setDefaultOutcome(probe);
      var files = new FakeMediaFileRepository();
      var file =
          files.save(
              MediaFile.builder()
                  .filepathUri(FilepathCodec.encode(source))
                  .filename(source.getFileName().toString())
                  .status(MediaFileStatus.MATCHED)
                  .build());
      var properties =
          StreamingProperties.builder()
              .maxConcurrentTranscodes(2)
              .targetSegmentDuration(Duration.ofSeconds(6))
              .build();
      var registry = new InMemoryStreamSessionRegistry();
      var lifecycle =
          StreamingRigFixture.streamingRigBuilder()
              .transcodeExecutor(executor)
              .segmentStore(segments)
              .properties(properties)
              .runtimeRegistry(registry)
              .heldRequests(server.heldSegmentRequests())
              .build();
      delivery = lifecycle.coordinator();
      streams =
          HlsStreamingService.builder()
              .mediaFileRepository(files)
              .transcodeExecutor(executor)
              .segmentStore(segments)
              .playbackProbeService(playbackProbeServiceBuilder(probes).build())
              .transcodeDecisionService(
                  new TranscodeDecisionService(properties, new SimpleMeterRegistry()))
              .qualityLadderService(new QualityLadderService())
              .properties(properties)
              .authorityGate((_, _) -> true)
              .runtimeRegistry(registry)
              .producerLifecycle(lifecycle.lifecycle())
              .deliveryCoordinator(delivery)
              .build();
      sessions =
          PlaybackSessionService.builder()
              .streamingService(streams)
              .authorityGate((_, _) -> true)
              .maximumFormatAttempts(3)
              .sessionRetention(Duration.ofMinutes(1))
              .build();
      playlists = new HlsPlaylistService(properties);
      command =
          StartPlaybackSessionCommand.builder()
              .mediaFileId(file.getId())
              .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
              .options(
                  StreamingOptions.builder()
                      .quality(VideoQuality.AUTO)
                      .supportedCodecs(List.of("h264"))
                      .build())
              .build();
    }

    private StreamSession awaitReplacement(PlaybackSessionSnapshot initial) {
      var request =
          PlaybackSessionRequest.builder()
              .playbackSessionId(initial.playbackSessionId())
              .identity(command.identity())
              .build();
      var ready =
          await()
              .atMost(15, TimeUnit.SECONDS)
              .until(
                  () -> sessions.access(request).orElseThrow(),
                  snapshot ->
                      snapshot.formatAttemptCount() == 2
                          && snapshot.currentFormatAttempt()
                              instanceof PlaybackFormatAttempt.Ready);
      return ((PlaybackFormatAttempt.Ready) ready.currentFormatAttempt()).session();
    }

    private byte[] deliver(StreamSession session, String variant, String name) {
      var result = delivery.deliver(session.getSessionId(), variant, variant + "/" + name);
      assertThat(result).isInstanceOf(SegmentDelivery.Ready.class);
      return ((SegmentDelivery.Ready) result).data();
    }

    @Override
    public void close() {
      sessions.close();
    }
  }
}
