package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fakes.FakeLearnedStreamEndRepository;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.streaming.HlsPlaylistService;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.LearnedStreamEndListener;
import com.streamarr.server.services.streaming.PlaybackFormatAttempt;
import com.streamarr.server.services.streaming.PlaybackFormatChange;
import com.streamarr.server.services.streaming.PlaybackProbeService;
import com.streamarr.server.services.streaming.PlaybackSessionRequest;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import com.streamarr.server.services.streaming.PlaybackSessionSnapshot;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.SegmentDelivery;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.ShortCopyListener;
import com.streamarr.server.services.streaming.StartPlaybackSessionCommand;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.events.CopiedVariantRanShortEvent;
import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import com.streamarr.server.services.streaming.local.InMemoryStreamSessionRegistry;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.local.SegmentFolderLock;
import com.streamarr.server.support.BoundedTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

  private final WorkerEvents events = new WorkerEvents();
  private Path sourceRoot;
  private LocalSegmentStore segments;
  private WorkerSessionServer server;
  private WorkerContainerFixture worker;

  @BeforeEach
  void startWorker() throws Exception {
    sourceRoot = Files.createDirectory(tempDir.resolve("media"));
    segments = new LocalSegmentStore(tempDir.resolve("segments"), new SegmentFolderLock());
    server =
        WorkerSessionServer.builder()
            .configuration(serverConfigurationBuilder().build())
            .eventPublisher(events)
            .meterRegistry(new SimpleMeterRegistry())
            .segmentStore(segments)
            .build();
    worker =
        WorkerContainerFixture.builder()
            .workerSessions(server)
            .sourceNamespaceId(SOURCE_NAMESPACE)
            .sourceRoot(sourceRoot)
            .availableSlots(2)
            .build();
    server.start();
    worker.start();
  }

  @AfterEach
  void stopWorker() {
    try (var _ = server;
        var _ = worker) {
      // Closes the worker, then the server that it connects to.
    } finally {
      segments.shutdown();
    }
  }

  @Test
  @DisplayName(
      "Should replace a rejected copy with a matching encoded playlist when the source changes after probing")
  void shouldReplaceARejectedCopyWithAMatchingEncodedPlaylistWhenTheSourceChangesAfterProbing()
      throws Exception {
    var mismatches = events.collect(VideoCopyOutputMismatchEvent.class);
    var source = worker.generateMedia(sourceRoot.resolve("movie.mp4"), sourceArguments("3.1", 2));
    var probe = probe(source);
    assertThat(probe.mediaProbe().videoPlaybackFacts().orElseThrow().codecString())
        .contains("avc1.64001F");
    try (var playback = new PlaybackRig(autoPlayback(source, probe))) {
      worker.generateMedia(source, sourceArguments("3.2", 2));

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
  }

  @Test
  @DisplayName(
      "Should end playback at the copy's end without another format attempt when the copy runs short after every source stream ended")
  void
      shouldEndPlaybackAtTheCopysEndWithoutAnotherFormatAttemptWhenTheCopyRunsShortAfterEverySourceStreamEnded()
          throws Exception {
    var source = worker.generateMedia(sourceRoot.resolve("movie.mp4"), sourceArguments("3.1", 24));
    var containerProbe = probe(source);
    worker.generateMedia(source, sourceArguments("3.1", 4));
    // The container and its keyframe index still describe 24 s, while the video stream's own
    // header says that it ends at 4 s.
    var storedProbe = withStreamsOf(containerProbe, probe(source));
    try (var playback = new PlaybackRig(autoPlayback(source, storedProbe))) {
      var started = accepted(playback.sessions.start(playback.command));
      var copy = ((PlaybackFormatAttempt.Ready) started.currentFormatAttempt()).session();
      assertThat(copy.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
      var copyAttempt = copy.getHandle().orElseThrow().attemptId();

      assertThat(playback.deliverDefault(copy, "segment0.m4s"))
          .isInstanceOf(SegmentDelivery.Ready.class);
      await().atMost(15, TimeUnit.SECONDS).until(copy::getNormalEnd, Optional::isPresent);
      try (var pastTheEnd =
          BoundedTask.start(() -> playback.deliverDefault(copy, "segment1.m4s"))) {
        // Only a request past the normal end waits for a wake without a deadline.
        await().atMost(5, TimeUnit.SECONDS).until(pastTheEnd::isAwaitingALatchWithoutDeadline);
        assertThat(copy.getNormalEnd()).contains(Duration.ofSeconds(6));
        assertThat(copy.getHandle().orElseThrow().attemptId()).isEqualTo(copyAttempt);
        var snapshot = playback.sessions.access(playback.requestFor(started)).orElseThrow();
        assertThat(snapshot.formatAttemptCount()).isOne();
        assertThat(snapshot.formatChange()).isEmpty();
        assertThat(snapshot.currentFormatAttempt())
            .isEqualTo(new PlaybackFormatAttempt.Ready(copy));
        playback.sessions.close(playback.requestFor(started));
        assertThat(pastTheEnd.await(Duration.ofSeconds(5)))
            .isEqualTo(new SegmentDelivery.SessionEnded());
      }
    }
  }

  @Test
  @DisplayName(
      "Should fall back to an encode in Auto when Original's copy runs short while the video still has media")
  void shouldFallBackToAnEncodeInAutoWhenOriginalsCopyRunsShortWhileTheVideoStillHasMedia()
      throws Exception {
    var source = worker.generateMedia(sourceRoot.resolve("movie.mp4"), sourceArguments("3.1", 24));
    // The probe still says that the video runs to 24 s.
    var storedProbe = probe(source);
    worker.generateMedia(source, sourceArguments("3.1", 4));
    var played =
        PlayedSource.builder()
            .file(source)
            .storedProbe(storedProbe)
            .quality(VideoQuality.ORIGINAL)
            .build();
    try (var playback = new PlaybackRig(played)) {
      var started = accepted(playback.sessions.start(playback.command));
      var copy = ((PlaybackFormatAttempt.Ready) started.currentFormatAttempt()).session();
      assertThat(copy.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();

      assertThat(playback.deliverDefault(copy, "segment0.m4s"))
          .isInstanceOf(SegmentDelivery.Ready.class);
      var encode = playback.awaitReplacement(started);

      assertThat(encode.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
      assertThat(copy.getNormalEnd()).isEmpty();
      var snapshot = playback.sessions.access(playback.requestFor(started)).orElseThrow();
      assertThat(snapshot.playbackChoice()).isEqualTo(VideoQuality.AUTO);
      assertThat(snapshot.formatChange()).contains(PlaybackFormatChange.COPY_FAILED);
      assertThat(playback.streams.getAllSessions()).containsExactly(encode);
    }
  }

  @Test
  @DisplayName(
      "Should fall back to an encode when the copy's report says that the audio still has media"
          + " after the copy's end, although the stored probe says that every stream ended there")
  void shouldFallBackToAnEncodeWhenTheCopysReportSaysTheAudioOutlastsTheCopyAgainstTheProbe()
      throws Exception {
    var source =
        worker.generateMedia(sourceRoot.resolve("movie.mp4"), soundedSourceArguments(24, 24));
    var containerProbe = probe(source);
    worker.generateMedia(source, soundedSourceArguments(4, 4));
    // The container and its keyframe index describe 24 s, and the stored streams end at 4 s.
    var storedProbe = withStreamsOf(containerProbe, probe(source));
    // The file now holds 24 s of audio beside its 4 s of video, which the probe never saw.
    worker.generateMedia(source, soundedSourceArguments(4, 24));
    try (var playback = new PlaybackRig(autoPlayback(source, storedProbe))) {
      var started = accepted(playback.sessions.start(playback.command));
      var copy = ((PlaybackFormatAttempt.Ready) started.currentFormatAttempt()).session();
      assertThat(copy.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();

      assertThat(playback.deliverDefault(copy, "segment0.m4s"))
          .isInstanceOf(SegmentDelivery.Ready.class);
      var encode = playback.awaitReplacement(started);

      assertThat(encode.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
      assertThat(copy.getNormalEnd()).isEmpty();
      assertThat(playback.streams.getAllSessions()).containsExactly(encode);
    }
  }

  @Test
  @DisplayName(
      "Should read each stream's end that the first job attempt learned when the stored probe"
          + " states no end times")
  void shouldReadEachStreamsEndThatTheFirstJobAttemptLearnedWhenTheStoredProbeStatesNoEndTimes()
      throws Exception {
    var source =
        worker.generateMedia(sourceRoot.resolve("movie.mp4"), soundedSourceArguments(12, 6));
    var storedProbe = withoutStreamEndTimes(probe(source));
    try (var playback = new PlaybackRig(autoPlayback(source, storedProbe))) {
      var started = accepted(playback.sessions.start(playback.command));
      var session = ((PlaybackFormatAttempt.Ready) started.currentFormatAttempt()).session();

      assertThat(playback.deliverDefault(session, "segment0.m4s"))
          .isInstanceOf(SegmentDelivery.Ready.class);
      await()
          .atMost(30, TimeUnit.SECONDS)
          .until(() -> learnedEndsOf(playback.readProbe()).size() == 2);

      assertThat(learnedEndsOf(playback.readProbe()))
          .as("the end of the 12 s video and of the 6 s audio, read from the source")
          .satisfiesExactly(
              video -> assertThat(video.toMillis()).isCloseTo(12_000, within(50L)),
              audio -> assertThat(audio.toMillis()).isCloseTo(6_000, within(50L)));
    }
  }

  private static SourceFileSnapshot snapshotOf(Path file) {
    try {
      return SourceFileSnapshot.of(Files.readAttributes(file, BasicFileAttributes.class));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<Duration> learnedEndsOf(MediaProbe probe) {
    return probe.streams().stream().flatMap(stream -> stream.endTime().stream()).toList();
  }

  // A container header that states no stream end times, as a live-written file's does.
  private static ProbeOutcome.Success withoutStreamEndTimes(ProbeOutcome.Success probed) {
    return new ProbeOutcome.Success(
        probed.container(),
        probed.streams().stream()
            .map(stream -> stream.toBuilder().endTime(Optional.empty()).build())
            .toList(),
        probed.keyframes(),
        probed.chosenVideoStream(),
        probed.streamTitlesOmitted(),
        probed.streamMeasurements());
  }

  private ProbeOutcome.Success probe(Path source) {
    var outcome =
        new RemoteFfprobeService(server, SOURCE_NAMESPACE, source.getParent())
            .probe(
                ProbeExecutionRequest.builder()
                    .sourcePath(source)
                    .attemptId(UUID.randomUUID())
                    .probeVersion(ProbeVersion.CURRENT)
                    .priority(ProbePriority.BACKGROUND)
                    .build());
    assertThat(outcome).isInstanceOf(ProbeOutcome.Success.class);
    return (ProbeOutcome.Success) outcome;
  }

  private static ProbeOutcome.Success withStreamsOf(
      ProbeOutcome.Success container, ProbeOutcome.Success streams) {
    return new ProbeOutcome.Success(
        container.container(),
        streams.streams(),
        container.keyframes(),
        container.chosenVideoStream(),
        container.streamTitlesOmitted(),
        container.streamMeasurements());
  }

  private static PlayedSource autoPlayback(Path file, ProbeOutcome.Success storedProbe) {
    return PlayedSource.builder()
        .file(file)
        .storedProbe(storedProbe)
        .quality(VideoQuality.AUTO)
        .build();
  }

  private static List<String> sourceArguments(String level, int seconds) {
    return List.of(
        "-f",
        "lavfi",
        "-i",
        "testsrc2=size=320x180:rate=24",
        "-t",
        Integer.toString(seconds),
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

  // H.264 that the default declaration copies, beside AAC that it copies, each of its own length.
  private static List<String> soundedSourceArguments(int videoSeconds, int audioSeconds) {
    return List.of(
        "-f",
        "lavfi",
        "-i",
        "testsrc2=size=320x180:rate=24:duration=" + videoSeconds,
        "-f",
        "lavfi",
        "-i",
        "sine=frequency=440:sample_rate=48000:duration=" + audioSeconds,
        "-map",
        "0:v",
        "-map",
        "1:a",
        "-c:v",
        "libx264",
        "-profile:v",
        "high",
        "-level:v",
        "3.1",
        "-pix_fmt",
        "yuv420p",
        "-g",
        "24",
        "-c:a",
        "aac");
  }

  private static byte[] combine(byte[] initialization, byte[] media) {
    return ByteBuffer.allocate(initialization.length + media.length)
        .put(initialization)
        .put(media)
        .array();
  }

  @Builder
  private record PlayedSource(
      @NonNull Path file,
      ProbeOutcome.@NonNull Success storedProbe,
      @NonNull VideoQuality quality) {}

  // Hands each event that the worker session server publishes to the listeners of its type, as
  // Spring's event multicaster does.
  private static final class WorkerEvents implements ApplicationEventPublisher {
    private final List<Consumer<Object>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publishEvent(Object event) {
      listeners.forEach(listener -> listener.accept(event));
    }

    private <T> void listen(Class<T> type, Consumer<T> listener) {
      listeners.add(
          event -> {
            if (type.isInstance(event)) {
              listener.accept(type.cast(event));
            }
          });
    }

    private <T> BlockingQueue<T> collect(Class<T> type) {
      var published = new LinkedBlockingQueue<T>();
      listen(type, published::add);
      return published;
    }
  }

  private final class PlaybackRig implements AutoCloseable {
    private final HlsStreamingService streams;
    private final PlaybackSessionService sessions;
    private final StartPlaybackSessionCommand command;
    private final SegmentDeliveryCoordinator delivery;
    private final HlsPlaylistService playlists;
    private final PlaybackProbeService probeService;
    private final LearnedStreamEndListener learning;
    private final UUID mediaFileId;

    private PlaybackRig(PlayedSource played) {
      var source = played.file();
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE, source.getParent());
      var probes = new FakeMediaFileContainerInfoRepository();
      var files = new FakeMediaFileRepository();
      var file =
          files.save(
              MediaFile.builder()
                  .filepathUri(FilepathCodec.encode(source))
                  .filename(source.getFileName().toString())
                  .status(MediaFileStatus.MATCHED)
                  .build());
      // The stored outcome holds the snapshot of the file as it lies on disk now.
      probes.publish(
          ProbePublication.builder()
              .mediaFileId(file.getId())
              .snapshot(snapshotOf(source))
              .probeVersion(ProbeVersion.CURRENT)
              .outcome(played.storedProbe())
              .build());
      var properties =
          StreamingProperties.builder()
              .maxConcurrentTranscodes(2)
              .targetSegmentDuration(Duration.ofSeconds(6))
              .build();
      var registry = new InMemoryStreamSessionRegistry();
      var learnedEnds = new FakeLearnedStreamEndRepository(probes);
      probeService = playbackProbeServiceBuilder(probes).learnedStreamEnds(learnedEnds).build();
      mediaFileId = file.getId();
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
              .languagePreferences(noLanguagePreferences())
              .meterRegistry(new SimpleMeterRegistry())
              .mediaFileRepository(files)
              .transcodeExecutor(executor)
              .segmentStore(segments)
              .playbackProbeService(probeService)
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
              .eventPublisher(events)
              .streamingService(streams)
              .authorityGate((_, _) -> true)
              .maximumFormatAttempts(3)
              .build();
      events.listen(VideoCopyOutputMismatchEvent.class, sessions::onVideoCopyOutputMismatch);
      learning = new LearnedStreamEndListener(registry, learnedEnds, new SimpleMeterRegistry());
      events.listen(JobAttemptCompletedEvent.class, learning::onJobAttemptCompleted);
      var shortCopies = new ShortCopyListener(lifecycle.lifecycle(), events);
      events.listen(JobAttemptCompletedEvent.class, shortCopies::onJobAttemptCompleted);
      events.listen(CopiedVariantRanShortEvent.class, sessions::onCopiedVariantRanShort);
      playlists = new HlsPlaylistService(properties);
      command =
          StartPlaybackSessionCommand.builder()
              .mediaFileId(file.getId())
              .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
              .options(StreamingOptions.builder().quality(played.quality()).build())
              .capabilityDeclaration(defaultDeclarationBuilder().build())
              .build();
    }

    private PlaybackSessionRequest requestFor(PlaybackSessionSnapshot snapshot) {
      return PlaybackSessionRequest.builder()
          .playbackSessionId(snapshot.playbackSessionId())
          .identity(command.identity())
          .build();
    }

    private StreamSession awaitReplacement(PlaybackSessionSnapshot initial) {
      var request = requestFor(initial);
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

    private MediaProbe readProbe() {
      return probeService
          .read(mediaFileId)
          .fold(
              probe -> probe,
              rejections -> {
                throw new AssertionError("Expected a stored probe: " + rejections);
              });
    }

    private SegmentDelivery deliverDefault(StreamSession session, String name) {
      return delivery.deliver(session.getSessionId(), StreamSession.defaultVariant(), name);
    }

    @Override
    public void close() {
      sessions.close();
      learning.close();
    }
  }
}
