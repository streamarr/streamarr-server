package com.streamarr.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Tag("IntegrationTest")
@DisplayName("Stream Controller Integration Tests")
@AutoConfigureMockMvc
class StreamControllerIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private StreamingService streamingService;
  @MockitoBean private SegmentStore segmentStore;

  @BeforeEach
  void setUp() {
    when(streamingService.getAllSessions()).thenReturn(Collections.emptyList());
    when(streamingService.getActiveSessionCount()).thenReturn(0);
  }

  @Test
  @DisplayName("shouldReturnMasterPlaylistWithCorrectContentType")
  void shouldReturnMasterPlaylistWithCorrectContentType() throws Exception {
    var session = buildMpegtsSession();
    when(streamingService.getSession(session.getSessionId())).thenReturn(Optional.of(session));

    var result =
        mockMvc
            .perform(get("/api/stream/{id}/master.m3u8", session.getSessionId()))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("application/vnd.apple.mpegurl");
    assertThat(result.getResponse().getContentAsString()).contains("#EXTM3U");
  }

  @Test
  @DisplayName("shouldReturn404ForInvalidSession")
  void shouldReturn404ForInvalidSession() throws Exception {
    var invalidId = UUID.randomUUID();
    when(streamingService.getSession(invalidId)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/stream/{id}/master.m3u8", invalidId))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("shouldServeTsSegmentWithCorrectContentType")
  void shouldServeTsSegmentWithCorrectContentType() throws Exception {
    var session = buildMpegtsSession();
    var segmentData = new byte[] {0x47, 0x00, 0x11, 0x10};
    when(streamingService.getSession(session.getSessionId())).thenReturn(Optional.of(session));
    when(segmentStore.waitForSegment(eq(session.getSessionId()), eq("segment0.ts"), any()))
        .thenReturn(true);
    when(segmentStore.readSegment(session.getSessionId(), "segment0.ts")).thenReturn(segmentData);

    var result =
        mockMvc
            .perform(get("/api/stream/{id}/segment0.ts", session.getSessionId()))
            .andExpect(status().isOk())
            .andReturn();

    assertThat(result.getResponse().getContentType()).isEqualTo("video/mp2t");
    assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(segmentData);
  }

  @Test
  @DisplayName("shouldReturn404WhenSegmentUnavailable")
  void shouldReturn404WhenSegmentUnavailable() throws Exception {
    var session = buildMpegtsSession();
    when(streamingService.getSession(session.getSessionId())).thenReturn(Optional.of(session));
    when(segmentStore.waitForSegment(eq(session.getSessionId()), eq("segment99.ts"), any()))
        .thenReturn(false);

    mockMvc
        .perform(get("/api/stream/{id}/segment99.ts", session.getSessionId()))
        .andExpect(status().isNotFound());
  }

  private StreamSession buildMpegtsSession() {
    return StreamSession.builder()
        .sessionId(UUID.randomUUID())
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
                .bitrate(5_000_000)
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
}
