package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Logging Tests")
class FfprobeExecutorLoggingTest {

  private static final Path SOURCE = Path.of("/media/movie.mkv");

  @TempDir Path temporaryDirectory;

  private final Logger logger = (Logger) LoggerFactory.getLogger(FfprobeExecutor.class);
  private final ListAppender<ILoggingEvent> events = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    events.start();
    logger.addAppender(events);
  }

  @AfterEach
  void detachLogs() {
    logger.detachAppender(events);
    events.stop();
  }

  @Test
  @DisplayName("Should log the attempt source and cause when ffprobe cannot start")
  void shouldLogTheAttemptSourceAndCauseWhenFfprobeCannotStart() {
    var attemptId = UUID.randomUUID();
    var request =
        ProbeRequest.newBuilder()
            .setProbeAttemptId(toProto(attemptId))
            .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
            .build();
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            _ -> {
              throw new UncheckedIOException(new IOException("binary unavailable"));
            });

    var result = executor.probe(SOURCE, request);

    assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
    assertThat(events.list)
        .anySatisfy(
            event -> {
              assertThat(event.getFormattedMessage())
                  .contains(attemptId.toString(), SOURCE.toString());
              assertThat(event.getThrowableProxy()).isNotNull();
              assertThat(event.getThrowableProxy().getCause().getMessage())
                  .isEqualTo("binary unavailable");
            });
  }

  @Test
  @DisplayName("Should log local exit diagnostics when ffprobe reports an execution failure")
  void shouldLogLocalExitDiagnosticsWhenFfprobeReportsAnExecutionFailure() throws Exception {
    var attemptId = UUID.randomUUID();
    var request =
        ProbeRequest.newBuilder()
            .setProbeAttemptId(toProto(attemptId))
            .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
            .build();
    var binary = temporaryDirectory.resolve("ffprobe");
    Files.writeString(
        binary,
        """
        #!/bin/sh
        printf '%s' '{"error":{"code":-12345,"string":"local probe diagnostic"}}'
        exit 17
        """);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));

    var result = FfprobeExecutor.forBinary(binary).probe(SOURCE, request);

    assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
    assertThat(result.toString()).doesNotContain("local probe diagnostic");
    assertThat(events.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anySatisfy(
            message ->
                assertThat(message)
                    .contains(
                        attemptId.toString(),
                        SOURCE.toString(),
                        "17",
                        "-12345",
                        "local probe diagnostic"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"not json", "null", ""})
  @DisplayName("Should log the exit status when ffprobe output cannot be interpreted")
  void shouldLogTheExitStatusWhenFfprobeOutputCannotBeInterpreted(String output) throws Exception {
    var binary = temporaryDirectory.resolve("ffprobe");
    Files.writeString(
        binary,
        """
        #!/bin/sh
        printf '%%s' '%s'
        exit 19
        """
            .formatted(output));
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
    var request = ProbeRequest.newBuilder().setProbeVersion(FfprobeExecutor.PROBE_VERSION).build();

    var result = FfprobeExecutor.forBinary(binary).probe(SOURCE, request);

    assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
    assertThat(events.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anySatisfy(message -> assertThat(message).contains(SOURCE.toString(), "code 19"));
  }
}
