package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("UnitTest")
@DisplayName("Ffprobe configuration tests")
class FfprobeConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(RemoteProbeConfiguration.class, WorkerSessionConfiguration.class)
          .withBean(SegmentStore.class, FakeSegmentStore::new)
          .withPropertyValues(
              "streaming.worker-session.loopback.enabled=true",
              "streaming.worker-session.loopback.port=0",
              "streaming.remote.source-namespace-id=cccccccc-cccc-cccc-cccc-cccccccccccc",
              "streaming.remote.source-root=/media");

  @Test
  @DisplayName("Should use worker probing when the server starts")
  void shouldUseWorkerProbingWhenTheServerStarts() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed().hasSingleBean(FfprobeService.class);
          var service = context.getBean(FfprobeService.class);
          assertThat(service).isInstanceOf(RemoteFfprobeService.class);
          var request =
              ProbeExecutionRequest.builder()
                  .sourcePath(Path.of("/media/movie.mkv"))
                  .attemptId(UUID.randomUUID())
                  .probeVersion(1)
                  .build();

          assertThatThrownBy(() -> service.probe(request))
              .isInstanceOf(ProbeExecutionException.class);
        });
  }
}
