package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.cancel;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.failure;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.processBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.StartProbeCommand;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Worker Probe Command Tests")
class WorkerProbeCommandTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should diagnose an ignored probe command when it targets another boot")
  void shouldDiagnoseAnIgnoredProbeCommandWhenItTargetsAnotherBoot(CapturedOutput output)
      throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().build();
    var otherBoot = UUID.randomUUID();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      var target = connection.registration().getWorker().toBuilder().setBootId(toProto(otherBoot));
      connection.deliver(
          EstablishWorkerSessionResponse.newBuilder()
              .setStartProbe(StartProbeCommand.newBuilder().setTarget(target).setRequest(request))
              .build());
      runtime.probes().drain();

      assertThat(connection.results()).isEmpty();
      assertThat(output)
          .contains(
              "Ignoring probe",
              fromProto(request.getProbeAttemptId()).toString(),
              fromProto(target.getWorkerId()).toString(),
              otherBoot.toString());
    }
  }

  @Test
  @DisplayName("Should preserve cancellation ownership when a probe command is duplicated")
  void shouldPreserveCancellationOwnershipWhenAProbeCommandIsDuplicated(CapturedOutput output)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> processBuilder().build());

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      start(connection, request);
      start(connection, request);

      cancel(connection, request);
      runtime.probes().drain();

      assertThat(connection.results())
          .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_CANCELLED));
      assertThat(output)
          .contains("duplicate probe", fromProto(request.getProbeAttemptId()).toString());
    }
  }
}
