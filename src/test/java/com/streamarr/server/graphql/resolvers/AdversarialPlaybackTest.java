package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.fixtures.PersistedProbeFixture.storedProbeBuilder;
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
import com.streamarr.server.fakes.CapturingProbeRequests;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.library.MediaFileProbeScheduler;
import com.streamarr.server.services.probe.PersistedProbeReader;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.PlaybackProbeService;
import com.streamarr.server.services.streaming.QualityLadderService;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.WorkerSessionListeners;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.services.watchprogress.SessionProgressService;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import com.streamarr.server.support.security.WithProfileContext;
import graphql.ExecutionResult;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
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
    outcomes.store(
        storedProbeBuilder(file.getId(), completeProbe(defaultProbeBuilder().build())).build());

    assertPayload(file, "TranscodeCapacityUnavailableError");
  }

  @Test
  @DisplayName(
      "Should return the typed capacity payload when video transcode has no connected worker")
  void shouldReturnTypedCapacityPayloadWhenVideoTranscodeHasNoConnectedWorker() throws Exception {
    var source = Files.writeString(directory.resolve("movie.mkv"), "media");
    var file = saveFile(source);
    outcomes.store(
        storedProbeBuilder(
                file.getId(), completeProbe(defaultProbeBuilder().videoCodec("hevc").build()))
            .build());

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
              assertThat(error.getPath()).containsExactly("createStreamSessionV2");
              assertThat(error.getExtensions())
                  .containsOnlyKeys("errorType", "code", "requestId")
                  .containsEntry("errorType", "INTERNAL")
                  .containsEntry("code", "INTERNAL");
              assertThat(error.getExtensions().get("requestId")).asString().isNotBlank();
            });
    assertThat(
            JsonPath.parse(result.toSpecification())
                .read("data.createStreamSessionV2", Object.class))
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

  private void assertPayload(MediaFile file, String errorType) {
    var result = requestSession(file);

    assertThat(result.getErrors()).as("GraphQL response: %s", result.toSpecification()).isEmpty();
    var document = JsonPath.parse(result.toSpecification());
    assertThat(document.read("data.createStreamSessionV2.session", Object.class)).isNull();
    List<String> errorTypes = document.read("data.createStreamSessionV2.userErrors[*].__typename");
    assertThat(errorTypes).containsExactly(errorType);
  }

  private ExecutionResult requestSession(MediaFile file) {
    return queries.execute(
        """
        mutation($id: ID!) {
          createStreamSessionV2(input: {mediaFileId: $id}) {
            session { id }
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
    CapturingProbeRequests probeRequests() {
      return new CapturingProbeRequests();
    }

    @Bean
    MediaFileProbeScheduler scheduler(
        PersistedProbeReader reader, CapturingProbeRequests requests, FileSystem sourceFileSystem) {
      return MediaFileProbeScheduler.builder()
          .mediaFileRepository(files)
          .reader(reader)
          .probeRequests(requests)
          .fileSystem(sourceFileSystem)
          .build();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    WorkerSessionServer sessions() {
      return WorkerSessionServer.forListeners(
          WorkerSessionListeners.builder().loopbackPort(OptionalInt.of(0)).build(), segments);
    }

    @Bean
    RemoteTranscodeExecutor executor(WorkerSessionServer sessions, FileSystem sourceFileSystem) {
      return new RemoteTranscodeExecutor(
          sessions, UUID.randomUUID(), sourceFileSystem.getPath("/media"));
    }

    @Bean
    HlsStreamingService streaming(
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
              .build();
      return HlsStreamingService.builder()
          .mediaFileRepository(files)
          .transcodeExecutor(executor)
          .segmentStore(segments)
          .playbackProbeService(new PlaybackProbeService(reader, events))
          .transcodeDecisionService(new TranscodeDecisionService())
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
