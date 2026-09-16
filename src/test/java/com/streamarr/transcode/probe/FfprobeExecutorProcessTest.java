package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Process Tests")
class FfprobeExecutorProcessTest {

  @TempDir Path temporaryDirectory;

  @Test
  @DisplayName("Should return an execution failure when the configured binary is unavailable")
  void shouldReturnAnExecutionFailureWhenTheConfiguredBinaryIsUnavailable() {
    var executor = FfprobeExecutor.forBinary(temporaryDirectory.resolve("missing-ffprobe"));

    assertThat(executor.probe(temporaryDirectory.resolve("movie.mkv"), request()).getFailure())
        .isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  @Test
  @Timeout(5)
  @DisplayName("Should finish probing when stdout and stderr exceed pipe capacity")
  void shouldFinishProbingWhenStdoutAndStderrExceedPipeCapacity() throws Exception {
    var binary =
        script(
            """
        dd if=/dev/zero bs=65536 count=4 >&2
        printf '%262144s' ''
        printf '%s' '{"streams":[{"codec_type":"video"}]}'
        """);

    var result =
        FfprobeExecutor.forBinary(binary).probe(temporaryDirectory.resolve("movie.mkv"), request());

    assertThat(result.hasMedia()).isTrue();
    assertThat(result.getMedia().getStreamsList()).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "Should terminate ffprobe before returning cancellation when output or exit is blocked")
  void shouldTerminateFfprobeBeforeReturningCancellationWhenOutputOrExitIsBlocked(
      boolean closeOutput) throws Exception {
    var output =
        closeOutput ? "printf '%s' '{\"streams\":[{\"codec_type\":\"video\"}]}'; exec 1>&-\n" : "";
    var binary =
        script(
            output
                + """
        printf '%s' "$$" > "$0.pid"
        exec sleep 60
        """);
    var pidFile = Path.of(binary + ".pid");
    var process = new AtomicReference<ProcessHandle>();
    var completion = new CompletableFuture<ProbeCompletion>();
    var executor = FfprobeExecutor.forBinary(binary);
    var source = temporaryDirectory.resolve("movie.mkv");
    var request = request();
    var task =
        Thread.ofVirtual()
            .start(
                () -> {
                  var result = executor.probe(source, request);
                  completion.complete(
                      new ProbeCompletion(
                          result,
                          Optional.ofNullable(process.get())
                              .map(ProcessHandle::isAlive)
                              .orElse(true),
                          Thread.currentThread().isInterrupted()));
                });

    try {
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> Files.exists(pidFile) && !Files.readString(pidFile).isBlank());
      process.set(ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).orElseThrow());
      task.interrupt();

      var completed = completion.get(3, TimeUnit.SECONDS);

      assertThat(completed.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_CANCELLED);
      assertThat(completed.result().getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
      assertThat(completed.result().getProbeVersion()).isEqualTo(request.getProbeVersion());
      assertThat(completed.processAlive()).isFalse();
      assertThat(completed.interrupted()).isTrue();
    } finally {
      Optional.ofNullable(process.get()).ifPresent(ProcessHandle::destroyForcibly);
      task.interrupt();
      task.join(Duration.ofSeconds(3));
    }
  }

  private record ProbeCompletion(
      ProbeAttemptResult result, boolean processAlive, boolean interrupted) {}

  @Test
  @DisplayName("Should pass fixed probe arguments when executing the configured binary")
  void shouldPassFixedProbeArgumentsWhenExecutingTheConfiguredBinary() throws Exception {
    var binary =
        script(
            """
        printf '%s\\n' "$@" > "$0.args"
        printf '%s' '{"streams":[{"codec_type":"video","codec_name":"h264"}]}'
        """);
    var source = temporaryDirectory.resolve("movie with spaces.mkv");

    var result = FfprobeExecutor.forBinary(binary).probe(source, request());

    assertThat(result.hasMedia()).isTrue();
    assertThat(result.getMedia().getStreams(0).getCodec()).isEqualTo("h264");
    assertThat(Files.readAllLines(Path.of(binary + ".args")))
        .containsExactly(
            "-v",
            "quiet",
            "-print_format",
            "json",
            "-show_streams",
            "-show_format",
            "-show_error",
            source.toString());
  }

  private ProbeRequest request() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
        .build();
  }

  private Path script(String body) throws Exception {
    var binary = temporaryDirectory.resolve("ffprobe");
    Files.writeString(binary, "#!/bin/sh\n" + body);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
    return binary;
  }
}
