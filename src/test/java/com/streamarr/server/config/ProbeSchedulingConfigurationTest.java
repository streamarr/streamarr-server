package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.task.ProbeTaskRequest;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("UnitTest")
@DisplayName("Probe scheduling configuration")
class ProbeSchedulingConfigurationTest {

  private final ProbeSchedulingConfiguration configuration = new ProbeSchedulingConfiguration();

  @Test
  @DisplayName("Should run scheduler executions on virtual threads when customizing db-scheduler")
  void shouldRunSchedulerExecutionsOnVirtualThreadsWhenCustomizingDbScheduler()
      throws InterruptedException, ExecutionException {
    var customizer =
        configuration.dbSchedulerCustomizer(
            configuration.probeTaskSerializer(), new DriverManagerDataSource());

    try (var executor = customizer.executorService().orElseThrow()) {
      assertThat(executor.submit(() -> Thread.currentThread().isVirtual()).get()).isTrue();
    }
  }

  @Test
  @DisplayName("Should round-trip a probe task request when serializing task data")
  void shouldRoundTripAProbeTaskRequestWhenSerializingTaskData() {
    var serializer = configuration.probeTaskSerializer();
    var request =
        ProbeTaskRequest.builder()
            .mediaFileId(UUID.randomUUID())
            .libraryId(UUID.randomUUID())
            .filepathUri("file:///library/movie.mkv")
            .snapshot(new SourceFileSnapshot(1234, Instant.parse("2026-09-11T10:00:00.123456789Z")))
            .probeVersion(1)
            .build();

    var restored = serializer.deserialize(ProbeTaskRequest.class, serializer.serialize(request));

    assertThat(new JsonMapper().readTree(serializer.serialize(request)).isObject()).isTrue();
    assertThat(restored).isEqualTo(request);
    assertThat(
            configuration
                .dbSchedulerCustomizer(serializer, new DriverManagerDataSource())
                .serializer())
        .contains(serializer);
  }
}
