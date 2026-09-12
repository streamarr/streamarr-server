package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegProcessManager;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.TranscodeCapabilityService;
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
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag("IntegrationTest")
@DisplayName("Worker Session Startup Integration Tests")
@TestPropertySource(
    properties = {
      "streaming.worker-session.loopback.enabled=true",
      "streaming.worker-session.loopback.port=0",
      "streaming.worker-session.mutual-tls.enabled=false",
      "spring.main.cloud-platform=kubernetes"
    })
class WorkerSessionStartupIT extends AbstractIntegrationTest {

  @Autowired private ApplicationContext context;
  @Autowired private MockMvc mockMvc;
  @Autowired private FfprobeService probe;
  @Autowired private WorkerSessionServer workerSessions;
  @Autowired private HealthEndpoint healthEndpoint;
  @Autowired private HealthEndpointGroups healthGroups;

  @Test
  @DisplayName("Should start with worker probing when no workers are connected")
  void shouldStartWithWorkerProbingWhenNoWorkersAreConnected() {
    assertThat(context.getBeansOfType(TranscodeExecutor.class).values())
        .singleElement()
        .isInstanceOf(RemoteTranscodeExecutor.class);
    assertThat(context.getBeansOfType(FfprobeService.class).values())
        .singleElement()
        .isInstanceOf(RemoteFfprobeService.class);
    assertThat(context.getBeansOfType(FfmpegCommandBuilder.class)).isEmpty();
    assertThat(context.getBeansOfType(FfmpegTranscodeEngine.class)).isEmpty();
    assertThat(context.getBeansOfType(TranscodeCapabilityService.class)).isEmpty();
    assertThat(context.getBeansOfType(FfmpegProcessManager.class)).isEmpty();
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
  void shouldKeepServerLivenessAndReadinessUpWhenNoWorkersAreConnected() throws Exception {
    assertThat(context.getBean(TranscodeExecutor.class).isHealthy()).isFalse();
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("DOWN"));
    for (var group : List.of("liveness", "readiness")) {
      assertThat(healthGroups.get(group)).isNotNull();
      assertThat(healthGroups.get(group).isMember("transcodeExecutor")).isFalse();
      assertThat(healthEndpoint.healthForPath(group).getStatus()).isEqualTo(Status.UP);
      mockMvc
          .perform(get("/actuator/health/" + group))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("UP"));
    }
  }
}
