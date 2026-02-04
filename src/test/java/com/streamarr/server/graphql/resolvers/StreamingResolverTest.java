package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(classes = {StreamingResolver.class})
@DisplayName("Streaming Resolver Tests")
class StreamingResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private StreamingService streamingService;

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
        .activeRequestCount(new AtomicInteger(0))
        .build();
  }

  @Test
  @DisplayName("Should return session DTO when creating stream session")
  void shouldReturnSessionDtoWhenCreatingStreamSession() {
    var sessionId = UUID.randomUUID();
    var session = buildSession(sessionId);
    when(streamingService.createSession(any(UUID.class), any(StreamingOptions.class)))
        .thenReturn(session);

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
    when(streamingService.seekSession(eq(sessionId), anyInt())).thenReturn(session);

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
    var sessionId = UUID.randomUUID();
    doNothing().when(streamingService).destroySession(sessionId);

    var mutation =
        String.format(
            """
            mutation {
              destroyStreamSession(sessionId: "%s")
            }
            """,
            sessionId);

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
}
