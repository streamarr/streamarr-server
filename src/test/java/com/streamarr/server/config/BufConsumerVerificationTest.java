package com.streamarr.server.config;

import static com.streamarr.server.support.ProcessTestSupport.awaitCompletion;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Buf Consumer Verification Tests")
class BufConsumerVerificationTest {

  private static final String COMMIT = "233fca715f49425581ec0a1b660be886";
  private static final String SDK_VERSION = "1.84.0.2.20230913231627.233fca715f49";

  @TempDir private Path workspace;

  private Map<String, String> environment = Map.of();

  @Test
  @DisplayName(
      "Should pass private registry credentials through Maven settings without exposing them")
  void shouldPassPrivateRegistryCredentialsThroughMavenSettingsWithoutExposingThem()
      throws Exception {
    registry();
    environment = Map.of("BUF_TOKEN", "test-only-registry-credential");

    var result = runVerifier();

    assertThat(result.exitCode()).withFailMessage(result.output()).isZero();
    assertThat(Files.readString(workspace.resolve("consumer-arguments")))
        .contains("--settings\n.github/buf-consumer/settings.xml\n")
        .doesNotContain(environment.get("BUF_TOKEN"));
    assertThat(result.output()).doesNotContain(environment.get("BUF_TOKEN"));
    assertThat(Files.readString(workspace.resolve("target/buf-consumer-pin.properties")))
        .doesNotContain(environment.get("BUF_TOKEN"));
  }

  @Test
  @DisplayName("Should reject a registry revision that differs from the merged contract")
  void shouldRejectARegistryRevisionThatDiffersFromTheMergedContract() throws Exception {
    registry();
    Files.writeString(workspace.resolve("remote-contract"), "another worker contract");

    var result = runVerifier();

    assertThat(result.exitCode()).isNotZero();
    assertThat(workspace.resolve("consumer-arguments")).doesNotExist();
    assertThat(workspace.resolve("target/buf-consumer-pin.properties")).doesNotExist();
  }

  @Test
  @DisplayName("Should compile the independent consumer against the current contract API")
  void shouldCompileTheIndependentConsumerAgainstTheCurrentContractApi() {
    var diagnostics = new ByteArrayOutputStream();

    var exitCode =
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                diagnostics,
                diagnostics,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                workspace.toString(),
                ".github/buf-consumer/src/main/java/com/streamarr/contract/PublishedContractConsumer.java");

    assertThat(exitCode).withFailMessage(diagnostics.toString()).isZero();
    assertThat(workspace.resolve("com/streamarr/contract/PublishedContractConsumer.class"))
        .isRegularFile();
  }

  @Test
  @DisplayName("Should verify a separate consumer against an immutable registry commit")
  void shouldVerifyASeparateConsumerAgainstAnImmutableRegistryCommit() throws Exception {
    registry();

    var result = runVerifier();

    assertThat(result.exitCode()).withFailMessage(result.output()).isZero();
    assertThat(Files.readString(workspace.resolve("consumer-arguments")))
        .contains("--file\n.github/buf-consumer/pom.xml\n", "-Dbuf.sdk.version=" + SDK_VERSION);
    assertThat(Files.readString(workspace.resolve("target/buf-consumer-pin.properties")))
        .contains(
            "module=buf.build/streamarr-org/transcode\n",
            "commit=" + COMMIT + "\n",
            "grpc.sdk.version=" + SDK_VERSION + "\n");
  }

  private void registry() throws Exception {
    var bin = Files.createDirectory(workspace.resolve("bin"));
    executable(
        bin.resolve("buf"),
        """
        #!/bin/bash
        set -eu
        case "$*" in
          'registry module commit resolve buf.build/streamarr-org/transcode:main --format json')
            echo '{"commit":"%s"}' ;;
          'registry sdk version --module=buf.build/streamarr-org/transcode:%s --plugin=buf.build/grpc/java:v1.84.0')
            echo '%s' ;;
          'build . --as-file-descriptor-set --exclude-source-info --output '*)
            cp local-contract "${!#}" ;;
          'build buf.build/streamarr-org/transcode:%s --as-file-descriptor-set --exclude-source-info --output '*)
            cp remote-contract "${!#}" ;;
          *) exit 91 ;;
        esac
        """
            .formatted(COMMIT, COMMIT, SDK_VERSION, COMMIT));
    executable(
        workspace.resolve("mvnw"),
        """
        #!/bin/bash
        set -eu
        printf '%s\n' "$@" > consumer-arguments
        """);
    Files.writeString(workspace.resolve("local-contract"), "worker contract");
    Files.writeString(workspace.resolve("remote-contract"), "worker contract");
  }

  private Result runVerifier() throws Exception {
    var builder =
        new ProcessBuilder(
                "bash",
                Path.of(".github/scripts/verify-buf-consumer.sh").toAbsolutePath().toString())
            .directory(workspace.toFile())
            .redirectErrorStream(true);
    builder.environment().put("PATH", workspace.resolve("bin") + ":" + System.getenv("PATH"));
    builder.environment().putAll(environment);
    var process = builder.start();
    awaitCompletion(process, Duration.ofSeconds(10), "consumer verification completed");
    return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes()));
  }

  private void executable(Path path, String content) throws Exception {
    Files.writeString(path, content);
    assertThat(path.toFile().setExecutable(true)).isTrue();
  }

  private record Result(int exitCode, String output) {}
}
