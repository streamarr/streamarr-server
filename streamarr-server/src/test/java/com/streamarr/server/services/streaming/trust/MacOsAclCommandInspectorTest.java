package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("macOS ACL Command Inspector Tests")
class MacOsAclCommandInspectorTest {

  private static final Path SECRET_PATH = Path.of("/authority/authority.p12");

  @TempDir Path directory;

  @ParameterizedTest(name = "{0}")
  @MethodSource("metadataWithoutExtendedAcl")
  void shouldReportNoExtendedAclForMetadata(String metadata) {
    var inspector =
        new MacOsAclCommandInspector(List.of("/bin/sh", "-c", "printf '%s\\n' '" + metadata + "'"));

    assertThat(inspector.hasExtendedAcl(SECRET_PATH)).isFalse();
  }

  @Test
  @DisplayName("Should protect and delete redirected ACL command output")
  void shouldProtectAndDeleteRedirectedAclCommandOutput() {
    var starter = new CapturingCommandStarter();
    var inspector =
        new MacOsAclCommandInspector(
            List.of("/bin/sh", "-c", "printf '%s\\n' '-rw-------  1 owner group 0 secret'"),
            Duration.ofSeconds(1),
            starter);

    assertThat(inspector.hasExtendedAcl(SECRET_PATH)).isFalse();
    assertThat(starter.outputPermissions()).isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertThat(starter.outputPath()).doesNotExist();
  }

  @Test
  @DisplayName("Should report extended ACL when metadata has the ACL marker")
  void shouldReportExtendedAclWhenMetadataHasAclMarker() {
    var inspector =
        new MacOsAclCommandInspector(
            List.of("/bin/sh", "-c", "printf '%s\\n' '-rw-------+ 1 owner group 0 secret'"));

    assertThat(inspector.hasExtendedAcl(SECRET_PATH)).isTrue();
  }

  @Test
  @DisplayName("Should report extended ACL when command emits an ACL entry")
  void shouldReportExtendedAclWhenCommandEmitsAclEntry() {
    var inspector =
        new MacOsAclCommandInspector(
            List.of(
                "/bin/sh",
                "-c",
                "printf '%s\\n' '-rw-------@ 1 owner group 0 secret'"
                    + " ' 0: everyone allow read'"));

    assertThat(inspector.hasExtendedAcl(SECRET_PATH)).isTrue();
  }

  @Test
  @DisplayName("Should report no access grant when ACL entries only deny access")
  void shouldReportNoAccessGrantWhenAclEntriesOnlyDenyAccess() {
    var inspector =
        new MacOsAclCommandInspector(
            List.of(
                "/bin/sh",
                "-c",
                "printf '%s\\n' '-rw-------+ 1 owner group 0 secret'"
                    + " ' 0: everyone deny delete'"));

    assertThat(inspector.hasAccessGrant(SECRET_PATH)).isFalse();
  }

  @Test
  @DisplayName("Should report access grant when an ACL entry allows access")
  void shouldReportAccessGrantWhenAclEntryAllowsAccess() {
    var inspector =
        new MacOsAclCommandInspector(
            List.of(
                "/bin/sh",
                "-c",
                "printf '%s\\n' '-rw-------+ 1 owner group 0 secret'"
                    + " ' 0: everyone allow read'"));

    assertThat(inspector.hasAccessGrant(SECRET_PATH)).isTrue();
  }

  @Test
  @DisplayName("Should fail closed when an ACL entry has unknown structure")
  void shouldFailClosedWhenAclEntryHasUnknownStructure() {
    var inspector =
        new MacOsAclCommandInspector(
            List.of(
                "/bin/sh",
                "-c",
                "printf '%s\\n' '-rw-------+ 1 owner group 0 secret' 'unknown ACL entry'"));

    assertThatThrownBy(() -> inspector.hasAccessGrant(SECRET_PATH))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("Failed to inspect extended ACLs");
  }

  @Test
  @DisplayName("Should fail closed when ACL entry index is not numeric")
  void shouldFailClosedWhenAclEntryIndexIsNotNumeric() {
    var inspector =
        new MacOsAclCommandInspector(
            List.of(
                "/bin/sh",
                "-c",
                "printf '%s\\n' '-rw-------+ 1 owner group 0 secret'"
                    + " ' x: everyone allow read'"));

    assertThatThrownBy(() -> inspector.hasAccessGrant(SECRET_PATH))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("Failed to inspect extended ACLs");
  }

  @Test
  @DisplayName("Should fail closed when ACL entry has no permissions")
  void shouldFailClosedWhenAclEntryHasNoPermissions() {
    var inspector =
        new MacOsAclCommandInspector(
            List.of(
                "/bin/sh",
                "-c",
                "printf '%s\\n' '-rw-------+ 1 owner group 0 secret' ' 0: everyone allow '"));

    assertThatThrownBy(() -> inspector.hasAccessGrant(SECRET_PATH))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("Failed to inspect extended ACLs");
  }

  @Test
  @DisplayName("Should drain ACL command output while the command is running")
  void shouldDrainAclCommandOutputWhileCommandIsRunning() {
    var inspector =
        new MacOsAclCommandInspector(
            List.of(
                "/bin/sh",
                "-c",
                "printf '%s\\n' '-rw-------+ 1 owner group 0 secret'; "
                    + "/usr/bin/yes ' 0: everyone allow read' | /usr/bin/head -n 5000"),
            Duration.ofSeconds(2));

    assertThat(inspector.hasExtendedAcl(SECRET_PATH)).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidAclCommandOutput")
  void shouldFailClosedWhenAclCommandOutputIsInvalid(String script) {
    var inspector = new MacOsAclCommandInspector(List.of("/bin/sh", "-c", script));

    assertThatThrownBy(() -> inspector.hasExtendedAcl(SECRET_PATH))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("Failed to inspect extended ACLs");
  }

  @Test
  @DisplayName("Should fail closed when ACL command cannot start")
  void shouldFailClosedWhenAclCommandCannotStart() {
    var starter = new CapturingCommandStarter();
    var inspector =
        new MacOsAclCommandInspector(
            List.of(directory.resolve("missing-acl-command").toString()),
            Duration.ofSeconds(1),
            starter);

    assertThatThrownBy(() -> inspector.hasExtendedAcl(SECRET_PATH))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("Failed to inspect extended ACLs")
        .hasCauseInstanceOf(java.io.IOException.class);
    assertThat(starter.outputPath()).doesNotExist();
  }

  @Test
  @DisplayName("Should terminate ACL command and fail closed when inspection times out")
  void shouldTerminateAclCommandAndFailClosedWhenInspectionTimesOut() {
    var starter = new CapturingCommandStarter();
    var inspector =
        new MacOsAclCommandInspector(
            List.of("/bin/sh", "-c", "exec /bin/sleep 30"), Duration.ofSeconds(1), starter);
    var failure = new AtomicReference<Throwable>();
    var inspection =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    inspector.hasExtendedAcl(SECRET_PATH);
                  } catch (CertificateAuthorityStoreException thrown) {
                    failure.set(thrown);
                  }
                });

    Process command = null;
    try {
      command = starter.awaitProcess();
      await().atMost(Duration.ofSeconds(2)).until(() -> !inspection.isAlive());
      var startedCommand = command;
      await().atMost(Duration.ofSeconds(2)).until(() -> !startedCommand.isAlive());

      assertThat(failure.get())
          .isInstanceOf(CertificateAuthorityStoreException.class)
          .hasMessageContaining("Failed to inspect extended ACLs");
      assertThat(starter.outputPath()).doesNotExist();
    } finally {
      inspection.interrupt();
      if (command != null) {
        command.destroyForcibly();
      }
    }
  }

  @Test
  @DisplayName("Should read metadata without waiting for descendant to close command output")
  void shouldReadMetadataWithoutWaitingForDescendantToCloseCommandOutput() {
    var childPidFile = directory.resolve("acl-command-child.pid");
    var starter = new CapturingCommandStarter();
    var inspector =
        new MacOsAclCommandInspector(
            List.of(
                "/bin/sh",
                "-c",
                "/bin/sleep 10 & printf '%s' \"$!\" > \"$1\"; "
                    + "printf '%s\\n' '-rw-------  1 owner group 0 secret'",
                "streamarr-acl-test",
                childPidFile.toString()),
            Duration.ofSeconds(1),
            starter);

    ProcessHandle child = null;
    try {
      assertThat(inspector.hasExtendedAcl(SECRET_PATH)).isFalse();

      child = ProcessHandle.of(awaitProcessId(childPidFile)).orElseThrow();
      assertThat(child.isAlive()).isTrue();
      assertThat(starter.outputPath()).doesNotExist();
    } finally {
      if (child != null) {
        child.destroyForcibly();
      }
    }
  }

  @Test
  @DisplayName("Should fail promptly when timed-out ACL command cannot be reaped")
  void shouldFailPromptlyWhenTimedOutAclCommandCannotBeReaped() {
    var process = new DelayedExitProcess(Duration.ofSeconds(2));
    var output = new AtomicReference<Path>();
    var inspector =
        new MacOsAclCommandInspector(
            List.of("/bin/ls", "--"),
            Duration.ofMillis(10),
            (_, outputPath) -> {
              output.set(outputPath);
              return process;
            });

    assertThatThrownBy(() -> inspector.hasExtendedAcl(SECRET_PATH))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("Failed to inspect extended ACLs");

    assertThat(process.hasExited()).isFalse();
    assertThat(output.get()).doesNotExist();
  }

  @Test
  @DisplayName(
      "Should terminate ACL command and preserve interruption when inspection is interrupted")
  void shouldTerminateAclCommandAndPreserveInterruptionWhenInspectionIsInterrupted() {
    var starter = new CapturingCommandStarter();
    var inspector =
        new MacOsAclCommandInspector(
            List.of("/bin/sh", "-c", "exec /bin/sleep 30"), Duration.ofSeconds(5), starter);
    var failure = new AtomicReference<Throwable>();
    var interrupted = new AtomicBoolean();
    var inspection =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    inspector.hasExtendedAcl(SECRET_PATH);
                  } catch (CertificateAuthorityStoreException thrown) {
                    failure.set(thrown);
                    interrupted.set(Thread.currentThread().isInterrupted());
                  }
                });

    Process command = null;
    try {
      command = starter.awaitProcess();
      inspection.interrupt();
      await().atMost(Duration.ofSeconds(2)).until(() -> !inspection.isAlive());
      var startedCommand = command;
      await().atMost(Duration.ofSeconds(2)).until(() -> !startedCommand.isAlive());

      assertThat(failure.get()).isInstanceOf(CertificateAuthorityStoreException.class);
      assertThat(interrupted.get()).isTrue();
      assertThat(starter.outputPath()).doesNotExist();
    } finally {
      inspection.interrupt();
      if (command != null) {
        command.destroyForcibly();
      }
    }
  }

  private static long awaitProcessId(Path pidFile) {
    await().atMost(Duration.ofSeconds(2)).until(() -> readPublishedProcessId(pidFile) >= 0L);
    return readPublishedProcessId(pidFile);
  }

  private static Stream<Arguments> metadataWithoutExtendedAcl() {
    return Stream.of(
        Arguments.of(
            Named.of(
                "Should report no extended ACL when command emits one metadata line",
                "-rw-------  1 owner group 0 secret")),
        Arguments.of(
            Named.of(
                "Should not confuse extended attributes with an extended ACL",
                "-rw-------@ 1 owner group 0 secret")),
        Arguments.of(
            Named.of(
                "Should report no extended ACL for directory metadata",
                "drwx------  1 owner group 0 authority")));
  }

  private static Stream<Arguments> invalidAclCommandOutput() {
    return Stream.of(
        Arguments.of(
            Named.of(
                "Should fail closed when ACL command exits unsuccessfully",
                "printf 'inspection failed\\n'; exit 7")),
        Arguments.of(Named.of("Should fail closed when ACL command emits no metadata", "exit 0")),
        Arguments.of(
            Named.of(
                "Should fail closed when ACL command emits unrecognized metadata",
                "printf 'unexpected output\\n'")),
        Arguments.of(
            Named.of(
                "Should fail closed when ACL metadata is truncated", "printf '%s\\n' 'short'")),
        Arguments.of(
            Named.of(
                "Should fail closed when ACL metadata has an unsupported file type",
                "printf '%s\\n' 'lrw-------  1 owner group 0 secret'")),
        Arguments.of(
            Named.of(
                "Should fail closed when ACL metadata has an invalid permission character",
                "printf '%s\\n' '-rw----?--  1 owner group 0 secret'")),
        Arguments.of(
            Named.of(
                "Should fail closed when permission character is impossible at its mode position",
                "printf '%s\\n' '-s--------  1 owner group 0 secret'")),
        Arguments.of(
            Named.of(
                "Should fail closed when ACL metadata has an invalid marker",
                "printf '%s\\n' '-rw-------? 1 owner group 0 secret'")));
  }

  private static long readPublishedProcessId(Path pidFile) {
    try {
      var contents = Files.readString(pidFile).trim();
      if (!contents.matches("[0-9]+")) {
        return -1L;
      }
      return Long.parseLong(contents);
    } catch (IOException | NumberFormatException _) {
      return -1L;
    }
  }

  private static final class DelayedExitProcess extends Process {

    private final CompletableFuture<Process> exit = new CompletableFuture<>();

    private DelayedExitProcess(Duration exitDelay) {
      CompletableFuture.delayedExecutor(exitDelay.toMillis(), TimeUnit.MILLISECONDS)
          .execute(() -> exit.complete(this));
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      exit.join();
      return 0;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
      return false;
    }

    @Override
    public int exitValue() {
      if (!exit.isDone()) {
        throw new IllegalThreadStateException();
      }
      return 0;
    }

    @Override
    public void destroy() {
      // Intentionally remains alive to simulate a process that cannot be reaped.
    }

    @Override
    public Process destroyForcibly() {
      return this;
    }

    @Override
    public CompletableFuture<Process> onExit() {
      return exit;
    }

    private boolean hasExited() {
      return exit.isDone();
    }
  }

  private static final class CapturingCommandStarter
      implements MacOsAclCommandInspector.CommandStarter {

    private final AtomicReference<Process> process = new AtomicReference<>();
    private final AtomicReference<Path> output = new AtomicReference<>();
    private final AtomicReference<Set<PosixFilePermission>> outputPermissions =
        new AtomicReference<>();

    @Override
    public Process start(List<String> command, Path outputPath) throws IOException {
      output.set(outputPath);
      outputPermissions.set(Files.getPosixFilePermissions(outputPath));
      var processBuilder =
          new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(outputPath.toFile());
      processBuilder.environment().clear();
      processBuilder.environment().put("LC_ALL", "C");
      var started = processBuilder.start();
      process.set(started);
      return started;
    }

    private Process awaitProcess() {
      await().atMost(Duration.ofSeconds(2)).until(() -> process.get() != null);
      return process.get();
    }

    private Path outputPath() {
      return output.get();
    }

    private Set<PosixFilePermission> outputPermissions() {
      return outputPermissions.get();
    }
  }
}
