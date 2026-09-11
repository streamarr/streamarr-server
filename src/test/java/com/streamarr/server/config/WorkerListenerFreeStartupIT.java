package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.ffmpeg.LocalFfprobeService;
import com.streamarr.server.services.streaming.ffmpeg.LocalTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@Tag("IntegrationTest")
@DisplayName("Worker Listener-Free Startup Integration Tests")
@TestPropertySource(
    properties = {
      "streaming.worker-session.loopback.enabled=false",
      "streaming.worker-session.mutual-tls.enabled=false",
      "streaming.remote.enabled=false"
    })
class WorkerListenerFreeStartupIT extends AbstractIntegrationTest {

  @Autowired private TranscodeExecutor executor;
  @Autowired private FfprobeService probe;
  @Autowired private WorkerSessionServer workerSessions;

  @Test
  @DisplayName("Should retain local execution when both worker listeners are disabled")
  void shouldRetainLocalExecutionWhenBothWorkerListenersAreDisabled() {
    assertThat(executor).isInstanceOf(LocalTranscodeExecutor.class);
    assertThat(probe).isInstanceOf(LocalFfprobeService.class);
    assertThat(workerSessions.availableSlots(UUID.randomUUID())).isZero();
    assertThatThrownBy(workerSessions::port).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(workerSessions::loopbackPort).isInstanceOf(IllegalStateException.class);
  }
}
