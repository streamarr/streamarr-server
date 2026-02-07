package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.services.streaming.StreamingService;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

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
                .audioCodec("aac")
                .containerFormat(ContainerFormat.MPEGTS)
                .needsKeyframeAlignment(true)
                .build())
        .options(StreamingOptions.builder().supportedCodecs(List.of("h264")).build())
        .createdAt(Instant.now())
        .lastAccessedAt(Instant.now())
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

    var id = dgsQueryExecutor.executeAndExtractJsonPath(mutation, "data.createStreamSession.id");
    var streamUrl =
        dgsQueryExecutor.executeAndExtractJsonPath(mutation, "data.createStreamSession.streamUrl");
    var transcodeMode =
        dgsQueryExecutor.executeAndExtractJsonPath(
            mutation, "data.createStreamSession.transcodeMode");

    assertThat(id).isEqualTo(sessionId.toString());
    assertThat(streamUrl.toString()).contains("/api/stream/" + sessionId + "/master.m3u8");
    assertThat(transcodeMode).isEqualTo("REMUX");
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

  @Test
  @DisplayName("Should return true when destroying session")
  void shouldReturnTrueWhenDestroyingSession() {
    var mutation =
        String.format(
            """
            mutation {
              destroyStreamSession(sessionId: "%s")
            }
            """,
            UUID.randomUUID());

    Boolean result =
        dgsQueryExecutor.executeAndExtractJsonPath(mutation, "data.destroyStreamSession");

    assertThat(result).isTrue();
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

    void setNextResult(StreamSession session) {
      this.nextResult = session;
    }

    @Override
    public StreamSession createSession(UUID mediaFileId, StreamingOptions options) {
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
    public void destroySession(UUID sessionId) {}

    @Override
    public Collection<StreamSession> getAllSessions() {
      return nextResult != null ? List.of(nextResult) : Collections.emptyList();
    }

    @Override
    public int getActiveSessionCount() {
      return nextResult != null ? 1 : 0;
    }

    @Override
    public void resumeSessionIfNeeded(UUID sessionId, String segmentName) {}
  }
}
