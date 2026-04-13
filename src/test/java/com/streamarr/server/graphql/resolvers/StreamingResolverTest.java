package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.watchprogress.SessionProgressService;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(classes = {StreamingResolver.class, StreamingResolverTest.TestConfig.class})
@DisplayName("Streaming Resolver Tests")
class StreamingResolverTest {

  private static final StubStreamingService STUB_SERVICE = new StubStreamingService();

  @TestConfiguration
  static class TestConfig {
    @Bean
    StreamingService streamingService() {
      return STUB_SERVICE;
    }
  }

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @MockitoBean private SessionProgressService sessionProgressService;
  @MockitoBean private WatchStatusService watchStatusService;

  private StreamSession buildSession(UUID sessionId) {
    return StreamSession.builder()
        .sessionId(sessionId)
        .mediaFileId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movie.mkv"))
        .mediaProbe(
            MediaProbe.builder()
                .duration(Duration.ofMinutes(120))
                .framerate(24.0)
                .width(1920)
                .height(1080)
                .videoCodec("h264")
                .audioCodec("aac")
                .bitrate(5_000_000L)
                .build())
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.REMUX)
                .videoCodecFamily("h264")
                .audioDecision(AudioDecision.copy("aac", 2, 0))
                .subtitleDecision(SubtitleDecision.exclude())
                .containerFormat(ContainerFormat.MPEGTS)
                .needsKeyframeAlignment(true)
                .build())
        .options(StreamingOptions.builder().supportedCodecs(List.of("h264")).build())
        .createdAt(Instant.now())
        .build();
  }

  @Test
  @DisplayName("Should return session DTO when creating stream session")
  void shouldReturnSessionDtoWhenCreatingStreamSession() {
    var sessionId = UUID.randomUUID();
    var session = buildSession(sessionId);
    STUB_SERVICE.setNextResult(session);

    var mutation =
        String.format(
            """
            mutation {
              createStreamSession(mediaFileId: "%s") {
                id
                streamUrl
                transcodeMode
              }
            }
            """,
            UUID.randomUUID());

    var context = dgsQueryExecutor.executeAndGetDocumentContext(mutation);
    String id = context.read("data.createStreamSession.id");
    String streamUrl = context.read("data.createStreamSession.streamUrl");
    String transcodeMode = context.read("data.createStreamSession.transcodeMode");

    assertThat(id).isEqualTo(sessionId.toString());
    assertThat(streamUrl).contains("/api/stream/" + sessionId + "/master.m3u8");
    assertThat(transcodeMode).isEqualTo("REMUX");
  }

  @Test
  @DisplayName("Should map GraphQL options input to streaming options when options provided")
  void shouldMapGraphqlOptionsInputToStreamingOptionsWhenOptionsProvided() {
    var sessionId = UUID.randomUUID();
    var session = buildSession(sessionId);
    STUB_SERVICE.setNextResult(session);

    var mutation =
        String.format(
            """
            mutation {
              createStreamSession(
                mediaFileId: "%s",
                options: {
                  quality: HIGH_720P,
                  supportedCodecs: ["h264"],
                  supportedAudioCodecs: ["aac", "ac3"],
                  maxAudioChannels: 6
                }
              ) {
                id
              }
            }
            """,
            UUID.randomUUID());

    dgsQueryExecutor.executeAndExtractJsonPath(mutation, "data.createStreamSession.id");

    var receivedOptions = STUB_SERVICE.getLastReceivedOptions();
    assertThat(receivedOptions.quality()).isEqualTo(VideoQuality.HIGH_720P);
    assertThat(receivedOptions.supportedCodecs()).containsExactly("h264");
    assertThat(receivedOptions.supportedAudioCodecs()).containsExactly("aac", "ac3");
    assertThat(receivedOptions.maxAudioChannels()).isEqualTo(6);
  }

  @Test
  @DisplayName("Should use default options when options input is null")
  void shouldUseDefaultOptionsWhenOptionsInputIsNull() {
    var sessionId = UUID.randomUUID();
    var session = buildSession(sessionId);
    STUB_SERVICE.setNextResult(session);

    var mutation =
        String.format(
            """
            mutation {
              createStreamSession(mediaFileId: "%s") {
                id
              }
            }
            """,
            UUID.randomUUID());

    dgsQueryExecutor.executeAndExtractJsonPath(mutation, "data.createStreamSession.id");

    var receivedOptions = STUB_SERVICE.getLastReceivedOptions();
    assertThat(receivedOptions.quality()).isEqualTo(VideoQuality.AUTO);
    assertThat(receivedOptions.supportedCodecs())
        .isEqualTo(StreamingOptions.DEFAULT_SUPPORTED_CODECS);
    assertThat(receivedOptions.supportedAudioCodecs())
        .isEqualTo(StreamingOptions.DEFAULT_SUPPORTED_AUDIO_CODECS);
    assertThat(receivedOptions.maxAudioChannels())
        .isEqualTo(StreamingOptions.DEFAULT_MAX_AUDIO_CHANNELS);
  }

  @Test
  @DisplayName("Should return error when create session media file ID is invalid")
  void shouldReturnErrorWhenMediaFileIdIsInvalid() {
    var result =
        dgsQueryExecutor.execute(
            """
            mutation {
              createStreamSession(mediaFileId: "not-a-uuid") {
                id
              }
            }
            """);

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().getFirst().getMessage()).contains("Invalid ID format");
  }

  @Test
  @DisplayName("Should return session DTO when seeking session")
  void shouldReturnSessionDtoWhenSeekingSession() {
    var sessionId = UUID.randomUUID();
    var session = buildSession(sessionId);
    STUB_SERVICE.setNextResult(session);

    var mutation =
        String.format(
            """
            mutation {
              seekStreamSession(sessionId: "%s", positionSeconds: 300) {
                id
                streamUrl
              }
            }
            """,
            sessionId);

    var id = dgsQueryExecutor.executeAndExtractJsonPath(mutation, "data.seekStreamSession.id");

    assertThat(id).isEqualTo(sessionId.toString());
  }

  @Test
  @DisplayName("Should return error when seek session ID is invalid")
  void shouldReturnErrorWhenSeekSessionIdIsInvalid() {
    var result =
        dgsQueryExecutor.execute(
            """
            mutation {
              seekStreamSession(sessionId: "bad-id", positionSeconds: 300) {
                id
              }
            }
            """);

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().getFirst().getMessage()).contains("Invalid ID format");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("booleanMutations")
  @DisplayName("Should return true when executing boolean mutation")
  void shouldReturnTrueWhenExecutingBooleanMutation(
      String name, String mutation, String resultPath) {
    Boolean result =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format(mutation, UUID.randomUUID()), resultPath);

    assertThat(result).isTrue();
  }

  static Stream<Arguments> booleanMutations() {
    return Stream.of(
        Arguments.of(
            "destroyStreamSession",
            "mutation { destroyStreamSession(sessionId: \"%s\") }",
            "data.destroyStreamSession"),
        Arguments.of(
            "reportStreamSessionTimeline",
            "mutation { reportStreamSessionTimeline(sessionId: \"%s\", positionSeconds: 300, state: PLAYING) }",
            "data.reportStreamSessionTimeline"),
        Arguments.of("markWatched", "mutation { markWatched(id: \"%s\") }", "data.markWatched"),
        Arguments.of(
            "markUnwatched", "mutation { markUnwatched(id: \"%s\") }", "data.markUnwatched"));
  }

  @Test
  @DisplayName("Should return error when destroy session ID is invalid")
  void shouldReturnErrorWhenDestroySessionIdIsInvalid() {
    var result =
        dgsQueryExecutor.execute(
            """
            mutation {
              destroyStreamSession(sessionId: "bad-id")
            }
            """);

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().getFirst().getMessage()).contains("Invalid ID format");
  }

  private static class StubStreamingService implements StreamingService {

    private StreamSession nextResult;
    private StreamingOptions lastReceivedOptions;

    void setNextResult(StreamSession session) {
      this.nextResult = session;
    }

    StreamingOptions getLastReceivedOptions() {
      return lastReceivedOptions;
    }

    @Override
    public StreamSession createSession(UUID mediaFileId, StreamingOptions options) {
      this.lastReceivedOptions = options;
      return nextResult;
    }

    @Override
    public Optional<StreamSession> accessSession(UUID sessionId) {
      return Optional.ofNullable(nextResult);
    }

    @Override
    public StreamSession seekSession(UUID sessionId, int positionSeconds) {
      return nextResult;
    }

    @Override
    public void destroySession(UUID sessionId) {
      // no-op for test fake
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return nextResult != null ? List.of(nextResult) : Collections.emptyList();
    }

    @Override
    public int getActiveSessionCount() {
      return nextResult != null ? 1 : 0;
    }

    @Override
    public void resumeSessionIfNeeded(UUID sessionId, String segmentName) {
      // no-op for test fake
    }
  }

  @Test
  @DisplayName("Should return error when report timeline session ID is invalid")
  void shouldReturnErrorWhenReportTimelineSessionIdIsInvalid() {
    var result =
        dgsQueryExecutor.execute(
            """
            mutation {
              reportStreamSessionTimeline(sessionId: "bad-id", positionSeconds: 300, state: PLAYING)
            }
            """);

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().getFirst().getMessage()).contains("Invalid ID format");
  }

  @Test
  @DisplayName("Should return error when mark unwatched ID is invalid")
  void shouldReturnErrorWhenMarkUnwatchedIdIsInvalid() {
    var result =
        dgsQueryExecutor.execute(
            """
            mutation {
              markUnwatched(id: "bad-id")
            }
            """);

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().getFirst().getMessage()).contains("Invalid ID format");
  }
}
