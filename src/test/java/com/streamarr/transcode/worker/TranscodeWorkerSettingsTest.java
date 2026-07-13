package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Transcode Worker Settings Tests")
class TranscodeWorkerSettingsTest {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @Test
  @DisplayName("Should map required environment values with conservative defaults")
  void shouldMapRequiredEnvironmentValuesWithConservativeDefaults() {
    var settings = TranscodeWorkerSettings.fromEnvironment(requiredEnvironment());
    var worker = settings.workerConfiguration();

    assertThat(settings.controlPlaneHost()).isEqualTo("streamarr-server");
    assertThat(settings.controlPlanePort()).isEqualTo(9090);
    assertThat(settings.ffmpegPath()).isEqualTo("ffmpeg");
    assertThat(worker.workerId()).isEqualTo(WORKER_ID);
    assertThat(worker.availableSlots()).isEqualTo(1);
    assertThat(worker.sourceNamespaces()).containsEntry(SOURCE_NAMESPACE_ID, Path.of("/media"));
    assertThat(worker.segmentBasePath().toString()).contains("streamarr-worker-segments");
    assertThat(worker.tlsIdentity().certificate()).isEqualTo(Path.of("/tls/worker.crt"));
    assertThat(worker.tlsIdentity().privateKey()).isEqualTo(Path.of("/tls/worker.key"));
    assertThat(worker.tlsIdentity().trustBundle()).isEqualTo(Path.of("/tls/ca.crt"));
  }

  @Test
  @DisplayName("Should assign a fresh boot identity each time settings are loaded")
  void shouldAssignFreshBootIdentityEachTimeSettingsAreLoaded() {
    var first = TranscodeWorkerSettings.fromEnvironment(requiredEnvironment());
    var second = TranscodeWorkerSettings.fromEnvironment(requiredEnvironment());

    assertThat(first.workerConfiguration().workerId())
        .isEqualTo(second.workerConfiguration().workerId());
    assertThat(first.workerConfiguration().bootId())
        .isNotEqualTo(second.workerConfiguration().bootId());
  }

  @Test
  @DisplayName("Should fail fast when the control plane host is missing")
  void shouldFailFastWhenControlPlaneHostIsMissing() {
    var environment = new HashMap<>(requiredEnvironment());
    environment.remove("TRANSCODE_WORKER_CONTROL_PLANE_HOST");

    assertThatThrownBy(() -> TranscodeWorkerSettings.fromEnvironment(environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("TRANSCODE_WORKER_CONTROL_PLANE_HOST is required");
  }

  @Test
  @DisplayName("Should distinguish a missing UUID from an invalid UUID")
  void shouldDistinguishMissingUuidFromInvalidUuid() {
    var environment = new HashMap<>(requiredEnvironment());
    environment.remove("TRANSCODE_WORKER_SOURCE_NAMESPACE_ID");

    assertThatThrownBy(() -> TranscodeWorkerSettings.fromEnvironment(environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("TRANSCODE_WORKER_SOURCE_NAMESPACE_ID is required");
  }

  @Test
  @DisplayName("Should reject a non-positive worker slot count")
  void shouldRejectNonPositiveWorkerSlotCount() {
    var environment = new HashMap<>(requiredEnvironment());
    environment.put("TRANSCODE_WORKER_SLOTS", "0");

    assertThatThrownBy(() -> TranscodeWorkerSettings.fromEnvironment(environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("TRANSCODE_WORKER_SLOTS must be positive");
  }

  @Test
  @DisplayName("Should map explicit worker process settings")
  void shouldMapExplicitWorkerProcessSettings() {
    var environment = new HashMap<>(requiredEnvironment());
    environment.put("TRANSCODE_WORKER_CONTROL_PLANE_PORT", "65535");
    environment.put("TRANSCODE_WORKER_SLOTS", "2");
    environment.put("TRANSCODE_WORKER_FFMPEG_PATH", "/usr/local/bin/ffmpeg");
    environment.put("TRANSCODE_WORKER_SEGMENT_BASE_PATH", "/transcode");

    var settings = TranscodeWorkerSettings.fromEnvironment(environment);

    assertThat(settings.controlPlanePort()).isEqualTo(65_535);
    assertThat(settings.ffmpegPath()).isEqualTo("/usr/local/bin/ffmpeg");
    assertThat(settings.workerConfiguration().availableSlots()).isEqualTo(2);
    assertThat(settings.workerConfiguration().segmentBasePath()).isEqualTo(Path.of("/transcode"));
  }

  @Test
  @DisplayName("Should explain invalid worker process settings")
  void shouldExplainInvalidWorkerProcessSettings() {
    assertInvalidSetting("TRANSCODE_WORKER_ID", "not-a-uuid", "TRANSCODE_WORKER_ID must be a UUID");
    assertInvalidSetting(
        "TRANSCODE_WORKER_CONTROL_PLANE_PORT",
        "65536",
        "TRANSCODE_WORKER_CONTROL_PLANE_PORT must not exceed 65535");
    assertInvalidSetting(
        "TRANSCODE_WORKER_SLOTS", "two", "TRANSCODE_WORKER_SLOTS must be an integer");
  }

  private void assertInvalidSetting(String key, String value, String expectedMessage) {
    var environment = new HashMap<>(requiredEnvironment());
    environment.put(key, value);

    assertThatThrownBy(() -> TranscodeWorkerSettings.fromEnvironment(environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(expectedMessage);
  }

  private Map<String, String> requiredEnvironment() {
    return Map.of(
        "TRANSCODE_WORKER_CONTROL_PLANE_HOST", "streamarr-server",
        "TRANSCODE_WORKER_ID", WORKER_ID.toString(),
        "TRANSCODE_WORKER_SOURCE_NAMESPACE_ID", SOURCE_NAMESPACE_ID.toString(),
        "TRANSCODE_WORKER_SOURCE_ROOT", "/media",
        "TRANSCODE_WORKER_TLS_CERTIFICATE", "/tls/worker.crt",
        "TRANSCODE_WORKER_TLS_PRIVATE_KEY", "/tls/worker.key",
        "TRANSCODE_WORKER_TLS_TRUST_BUNDLE", "/tls/ca.crt");
  }
}
