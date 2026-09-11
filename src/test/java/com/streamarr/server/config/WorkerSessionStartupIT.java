package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.ffmpeg.LocalTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.context.TestPropertySource;

@Tag("IntegrationTest")
@DisplayName("Worker Session Startup Integration Tests")
@TestPropertySource(
    properties = {
      "streaming.worker-session.loopback.enabled=true",
      "streaming.worker-session.loopback.port=0",
      "streaming.worker-session.mutual-tls.enabled=false",
      "streaming.remote.enabled=false",
      "spring.main.cloud-platform=kubernetes"
    })
class WorkerSessionStartupIT extends AbstractIntegrationTest {

  @Autowired private TranscodeExecutor executor;
  @Autowired private FfprobeService probe;
  @Autowired private WorkerSessionServer workerSessions;
  @Autowired private HealthEndpoint healthEndpoint;
  @Autowired private HealthEndpointGroups healthGroups;

  @Test
  @DisplayName("Should start with worker probing when no workers are connected")
  void shouldStartWithWorkerProbingWhenNoWorkersAreConnected() {
    assertThat(executor).isInstanceOf(LocalTranscodeExecutor.class);
    assertThat(probe).isInstanceOf(RemoteFfprobeService.class);
    assertThat(workerSessions.loopbackPort()).isPositive();
    assertThat(workerSessions.availableSlots(UUID.randomUUID())).isZero();
    var request =
        ProbeExecutionRequest.builder()
            .sourcePath(Path.of("/media/movie.mkv"))
            .attemptId(UUID.randomUUID())
            .probeVersion(1)
            .build();

    assertThatThrownBy(() -> probe.probe(request)).isInstanceOf(ProbeExecutionException.class);
  }

  @Test
  @DisplayName("Should keep server liveness and readiness up when no workers are connected")
  void shouldKeepServerLivenessAndReadinessUpWhenNoWorkersAreConnected() {
    for (var group : List.of("liveness", "readiness")) {
      assertThat(healthGroups.get(group)).isNotNull();
      assertThat(healthGroups.get(group).isMember("transcodeExecutor")).isFalse();
      assertThat(healthEndpoint.healthForPath(group).getStatus()).isEqualTo(Status.UP);
    }
  }
}
