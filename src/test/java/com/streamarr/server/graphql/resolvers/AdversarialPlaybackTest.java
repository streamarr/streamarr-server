package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.ProbeFixture.completeProbe;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.jayway.jsonpath.JsonPath;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.CapturingKeyframePacketScanRequests;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeProbeTaskRequests;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.library.MediaFileProbeTaskScheduler;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import com.streamarr.server.services.watchprogress.SessionProgressService;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import com.streamarr.server.support.security.WithProfileContext;
import graphql.ExecutionResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("Playback Mutation Error Channel Tests")
@Tag("UnitTest")
@EnableDgsTest
@WithProfileContext
@SpringBootTest(
    classes = {
      StreamingResolver.class,
      StreamarrDataFetcherExceptionHandler.class,
      AdversarialPlaybackTest.TestConfig.class,
      SecurityContextAuthorizationService.class,
      FakeAuthorizationDecider.class
    })
class AdversarialPlaybackTest {
  private Path directory;
  @Autowired FileSystem sourceFileSystem;
  @Autowired DgsQueryExecutor queries;
  @Autowired FakeMediaFileRepository files;
  @Autowired FakeMediaFileContainerInfoRepository outcomes;
  @MockitoBean UserAccountRepository accounts;
  @MockitoBean PlaybackTokenIssuer tokens;
  @MockitoBean SessionProgressService progress;
  @MockitoBean WatchStatusService watched;

  @BeforeEach
  void createSourceDirectory() throws IOException {
    directory =
        Files.createDirectories(sourceFileSystem.getPath("/media", UUID.randomUUID().toString()));
  }

  @Test
  @DisplayName("Should retain typed not-ready payload when the source disappears before playback")
  void shouldRetainTypedNotReadyPayloadWhenSourceDisappearsBeforePlayback() throws Exception {
    var source = Files.writeString(directory.resolve("movie.mkv"), "media");
    var file = saveFile(source);
    Files.delete(source);

    assertPayload(file, "MediaFileProbeNotReadyError");
  }

  @Test
  @DisplayName("Should retain the typed capacity payload when remux has no connected worker")
  void shouldRetainTypedCapacityPayloadWhenRemuxHasNoConnectedWorker() throws Exception {
    var source = Files.writeString(directory.resolve("movie.mkv"), "media");
    var file = saveFile(source);
    publishProbe(file, defaultProbeBuilder().build());
    // The decision copies the video only when the stored probe knows the keyframes.
    assertThat(outcomes.findKeyframeKnowledge(file.getId()))
        .get()
        .isInstanceOf(KeyframeKnowledge.Known.class);

    assertPayload(file, "TranscodeCapacityUnavailableError");
  }

  @Test
  @DisplayName(
      "Should retain the typed capacity payload when video transcode has no connected worker")
  void shouldRetainTypedCapacityPayloadWhenVideoTranscodeHasNoConnectedWorker() throws Exception {
    var source = Files.writeString(directory.resolve("movie.mkv"), "media");
    var file = saveFile(source);
    publishProbe(file, defaultProbeBuilder().videoCodec("hevc").build());

    assertPayload(file, "TranscodeCapacityUnavailableError");
  }

  @Test
  @DisplayName(
      "Should return a sanitized infrastructure error when the source contains a symbolic-link loop")
  void shouldReturnSanitizedInfrastructureErrorWhenSourceContainsASymbolicLinkLoop()
      throws Exception {
    var source = directory.resolve("movie.mkv");
    Files.createSymbolicLink(source, source.getFileName());
    var file = saveFile(source);

    var result = requestSession(file);

    assertThat(result.getErrors())
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.getMessage())
                  .isNotBlank()
                  .doesNotContain(source.toString(), file.getId().toString(), "Exception");
              assertThat(error.getPath()).containsExactly("createPlaybackSession");
              assertThat(error.getExtensions())
                  .containsOnlyKeys("errorType", "code", "requestId")
                  .containsEntry("errorType", "INTERNAL")
                  .containsEntry("code", "INTERNAL");
              assertThat(error.getExtensions().get("requestId")).asString().isNotBlank();
            });
    assertThat(
            JsonPath.parse(result.toSpecification())
                .read("data.createPlaybackSession", Object.class))
        .isNull();
  }

  @Test
  @DisplayName("Should return the typed not-ready payload when the source is accessible")
  void shouldReturnTypedNotReadyPayloadWhenSourceIsAccessible() throws Exception {
    var source = Files.writeString(directory.resolve("movie.mkv"), "media");
    var file = saveFile(source);

    assertPayload(file, "MediaFileProbeNotReadyError");
  }

  private MediaFile saveFile(Path source) {
    return files.save(
        MediaFile.builder()
            .libraryId(UUID.randomUUID())
            .filepathUri(FilepathCodec.encode(source))
            .filename(source.getFileName().toString())
            .status(MediaFileStatus.MATCHED)
            .build());
  }

  // Publishing keeps the outcome's keyframe knowledge, which a stored row alone does not carry.
  private void publishProbe(MediaFile file, MediaProbe probe) {
    assertThat(
            outcomes.publish(
                ProbePublication.builder()
                    .mediaFileId(file.getId())
                    .snapshot(new SourceFileSnapshot(1000, Instant.EPOCH))
                    .probeVersion(ProbeVersion.CURRENT)
                    .outcome(completeProbe(probe))
                    .build()))
        .isTrue();
  }

  private void assertPayload(MediaFile file, String errorType) {
    var result = requestSession(file);

    assertThat(result.getErrors()).as("GraphQL response: %s", result.toSpecification()).isEmpty();
    var document = JsonPath.parse(result.toSpecification());
    assertThat(document.read("data.createPlaybackSession.playbackSession", Object.class)).isNull();
    List<String> errorTypes = document.read("data.createPlaybackSession.userErrors[*].__typename");
    assertThat(errorTypes).containsExactly(errorType);
  }

  private ExecutionResult requestSession(MediaFile file) {
    return queries.execute(
        """
        mutation($id: ID!) {
          createPlaybackSession(input: {mediaFileId: $id}) {
            playbackSession { id }
            userErrors { __typename ... on MutationError { message } }
          }
        }
        """,
        Map.of("id", file.getId().toString()));
  }

  @TestConfiguration
  static class TestConfig {
    private final FakeMediaFileRepository files = new FakeMediaFileRepository();
    private final FakeMediaFileContainerInfoRepository outcomes =
        new FakeMediaFileContainerInfoRepository();
    private final FakeSegmentStore segments = new FakeSegmentStore();
    private final StreamingProperties properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(8)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofMinutes(1))
            .sessionRetention(Duration.ofHours(1))
            .build();

    @Bean
    PlaybackSessionService playbackSessions(HlsStreamingService streaming) {
      return PlaybackSessionService.builder()
          .streamingService(streaming)
          .authorityGate(new FakePlaybackAuthorityGate())
          .executor(Runnable::run)
          .maximumFormatAttempts(2)
          .sessionRetention(Duration.ofHours(1))
          .build();
    }

    @Bean
    FileSystem sourceFileSystem() {
      return Jimfs.newFileSystem(Configuration.unix());
    }

    @Bean
    FakeMediaFileRepository mediaFiles() {
      return files;
    }

    @Bean
    FakeMediaFileContainerInfoRepository probeOutcomes() {
      return outcomes;
    }

    @Bean
    StreamingProperties streamingProperties() {
      return properties;
    }

    @Bean
    PersistedProbeReader reader() {
      return new PersistedProbeReader(outcomes);
    }

    @Bean
    FakeProbeTaskRequests probeRequests() {
      return new FakeProbeTaskRequests(outcomes);
    }

    @Bean
    MediaFileProbeTaskScheduler scheduler(
        PersistedProbeReader reader, FakeProbeTaskRequests requests, FileSystem sourceFileSystem) {
      return MediaFileProbeTaskScheduler.builder()
          .mediaFileRepository(files)
          .reader(reader)
          .probeTaskRequests(requests)
          .packetScans(new CapturingKeyframePacketScanRequests())
          .fileSystem(sourceFileSystem)
          .build();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    WorkerSessionServer sessions() {
      return WorkerSessionServer.builder()
          .eventPublisher(new CapturingEventPublisher())
          .configuration(WorkerSessionServerConfiguration.builder().port(0).build())
          .segmentStore(segments)
          .meterRegistry(new SimpleMeterRegistry())
          .build();
    }

    @Bean
    RemoteTranscodeExecutor executor(WorkerSessionServer sessions, FileSystem sourceFileSystem) {
      return new RemoteTranscodeExecutor(
          sessions, UUID.randomUUID(), sourceFileSystem.getPath("/media"));
    }

    @Bean
    HlsStreamingService streaming(
        WorkerSessionServer sessions,
        RemoteTranscodeExecutor executor,
        PersistedProbeReader reader,
        ApplicationEventPublisher events) {
      var registry = new FakeRuntimeStreamSessionRegistry();
      var rig =
          StreamingRigFixture.streamingRigBuilder()
              .transcodeExecutor(executor)
              .segmentStore(segments)
              .properties(properties)
              .runtimeRegistry(registry)
              .heldRequests(sessions.heldSegmentRequests())
              .build();
      return HlsStreamingService.builder()
          .meterRegistry(new SimpleMeterRegistry())
          .mediaFileRepository(files)
          .transcodeExecutor(executor)
          .segmentStore(segments)
          .playbackProbeService(
              playbackProbeServiceBuilder(outcomes).eventPublisher(events).build())
          .transcodeDecisionService(
              new TranscodeDecisionService(properties, new SimpleMeterRegistry()))
          .qualityLadderService(new QualityLadderService())
          .properties(properties)
          .authorityGate(new FakePlaybackAuthorityGate())
          .runtimeRegistry(registry)
          .producerLifecycle(rig.lifecycle())
          .deliveryCoordinator(rig.coordinator())
          .build();
    }
  }
}
