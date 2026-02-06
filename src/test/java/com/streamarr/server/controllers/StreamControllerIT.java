package com.streamarr.server.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

@Tag("IntegrationTest")
@DisplayName("Stream Controller Integration Tests")
@AutoConfigureMockMvc
class StreamControllerIT extends AbstractIntegrationTest {

  private static final StubStreamingService STUB_SERVICE = new StubStreamingService();
  private static final FakeSegmentStore FAKE_SEGMENT_STORE = new FakeSegmentStore();
  private static final FakeTranscodeExecutor FAKE_EXECUTOR = new FakeTranscodeExecutor();

  @Autowired private MockMvc mockMvc;

  @TestBean StreamingService streamingService;
  @TestBean SegmentStore segmentStore;
  @TestBean TranscodeExecutor transcodeExecutor;

  static StreamingService streamingService() {
    return STUB_SERVICE;
  }

  static SegmentStore segmentStore() {
    return FAKE_SEGMENT_STORE;
  }

  static TranscodeExecutor transcodeExecutor() {
    return FAKE_EXECUTOR;
  }

  @Test
  @DisplayName("Should return master playlist with correct content type when session exists")
  void shouldReturnMasterPlaylistWithCorrectContentTypeWhenSessionExists() throws Exception {
    var session = StreamSessionFixture.buildMpegtsSession();
    STUB_SERVICE.addSession(session);

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
    mockMvc
        .perform(get("/api/stream/{id}/master.m3u8", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should serve TS segment with correct content type when segment is available")
  void shouldServeTsSegmentWithCorrectContentTypeWhenSegmentIsAvailable() throws Exception {
    var session = StreamSessionFixture.buildMpegtsSession();
    var segmentData = new byte[] {0x47, 0x00, 0x11, 0x10};
    STUB_SERVICE.addSession(session);
    FAKE_SEGMENT_STORE.addSegment(session.getSessionId(), "segment0.ts", segmentData);

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
    STUB_SERVICE.addSession(session);

    mockMvc
        .perform(get("/api/stream/{id}/segment99.ts", session.getSessionId()))
        .andExpect(status().isNotFound());
  }

  private static class StubStreamingService implements StreamingService {

    private final ConcurrentHashMap<UUID, StreamSession> sessions = new ConcurrentHashMap<>();

    void addSession(StreamSession session) {
      sessions.put(session.getSessionId(), session);
    }

    @Override
    public StreamSession createSession(UUID mediaFileId, StreamingOptions options) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<StreamSession> accessSession(UUID sessionId) {
      return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public StreamSession seekSession(UUID sessionId, int positionSeconds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void destroySession(UUID sessionId) {
      sessions.remove(sessionId);
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return Collections.unmodifiableCollection(sessions.values());
    }

    @Override
    public int getActiveSessionCount() {
      return sessions.size();
    }

    @Override
    public void resumeSessionIfNeeded(UUID sessionId, String segmentName) {}
  }
}
