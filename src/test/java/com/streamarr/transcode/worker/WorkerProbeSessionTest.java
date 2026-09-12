package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker probe session lifetime")
class WorkerProbeSessionTest {

  @Test
  @DisplayName("Should report unsupported version when this session has no probe producer")
  void shouldReportUnsupportedVersionWhenThisSessionHasNoProbeProducer() throws Exception {
    var result = new CompletableFuture<ProbeAttemptResult>();
    var request =
        ProbeRequest.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(1)
            .build();

    try (var session =
        new WorkerProbeSession(
            Optional.empty(), new WorkerMediaSourceResolver(Map.of()), result::complete)) {
      session.start(request);

      assertThat(result.get(5, TimeUnit.SECONDS).getFailure())
          .isEqualTo(ProbeFailure.PROBE_FAILURE_UNSUPPORTED_VERSION);
    }
  }

  @Test
  @DisplayName("Should ignore a late probe command when its session has shut down")
  void shouldIgnoreALateProbeCommandWhenItsSessionHasShutDown() {
    var results = new ConcurrentLinkedQueue<ProbeAttemptResult>();
    var request =
        ProbeRequest.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(1)
            .build();

    try (var session =
        new WorkerProbeSession(
            Optional.empty(), new WorkerMediaSourceResolver(Map.of()), results::add)) {
      session.shutdown();

      assertThatCode(() -> session.start(request)).doesNotThrowAnyException();
    }

    assertThat(results).isEmpty();
  }
}
