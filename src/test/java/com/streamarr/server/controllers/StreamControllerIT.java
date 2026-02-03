package com.streamarr.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
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
  @DisplayName("Should return master playlist with correct content type when session exists")
  void shouldReturnMasterPlaylistWithCorrectContentTypeWhenSessionExists() throws Exception {
    var session = StreamSessionFixture.buildMpegtsSession();
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
  @DisplayName("Should return 404 when session not found")
  void shouldReturn404WhenSessionNotFound() throws Exception {
    var invalidId = UUID.randomUUID();
    when(streamingService.getSession(invalidId)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/stream/{id}/master.m3u8", invalidId))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should serve TS segment with correct content type when segment is available")
  void shouldServeTsSegmentWithCorrectContentTypeWhenSegmentIsAvailable() throws Exception {
    var session = StreamSessionFixture.buildMpegtsSession();
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
  @DisplayName("Should return 404 when segment unavailable")
  void shouldReturn404WhenSegmentUnavailable() throws Exception {
    var session = StreamSessionFixture.buildMpegtsSession();
    when(streamingService.getSession(session.getSessionId())).thenReturn(Optional.of(session));
    when(segmentStore.waitForSegment(eq(session.getSessionId()), eq("segment99.ts"), any()))
        .thenReturn(false);

    mockMvc
        .perform(get("/api/stream/{id}/segment99.ts", session.getSessionId()))
        .andExpect(status().isNotFound());
  }
}
