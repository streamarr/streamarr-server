package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeStreamingService;
import com.streamarr.server.fixtures.StreamSessionFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Streaming Shutdown Hook Tests")
class StreamingShutdownHookTest {

  @Test
  @DisplayName("Should destroy all active sessions when shutdown hook fires")
  void shouldDestroyAllActiveSessionsWhenShutdownHookFires() {
    var service = new FakeStreamingService();
    var session1 = StreamSessionFixture.buildMpegtsSession();
    var session2 = StreamSessionFixture.buildMpegtsSession();
    service.addSession(session1);
    service.addSession(session2);

    var hook = new StreamingShutdownHook(service);
    hook.onShutdown();

    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(service.getDestroyedIds())
        .containsExactlyInAnyOrder(session1.getSessionId(), session2.getSessionId());
  }

  @Test
  @DisplayName("Should not throw when no active sessions exist during shutdown")
  void shouldNotThrowWhenNoActiveSessionsExistDuringShutdown() {
    var service = new FakeStreamingService();

    var hook = new StreamingShutdownHook(service);
    hook.onShutdown();

    assertThat(service.getActiveSessionCount()).isZero();
    assertThat(service.getDestroyedIds()).isEmpty();
  }
}
