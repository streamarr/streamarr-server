package com.streamarr.transcode.worker;

import com.streamarr.transcode.tls.PemTlsIdentity;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;

@Builder
record TranscodeWorkerSettings(
    String controlPlaneHost,
    int controlPlanePort,
    String ffmpegPath,
    TranscodeWorkerConfiguration workerConfiguration) {

  private static final String PREFIX = "TRANSCODE_WORKER_";

  static TranscodeWorkerSettings fromEnvironment(Map<String, String> environment) {
    var sourceNamespaceId = uuid(environment, PREFIX + "SOURCE_NAMESPACE_ID");
    var tlsIdentity =
        PemTlsIdentity.builder()
            .certificate(path(environment, PREFIX + "TLS_CERTIFICATE"))
            .privateKey(path(environment, PREFIX + "TLS_PRIVATE_KEY"))
            .trustBundle(path(environment, PREFIX + "TLS_TRUST_BUNDLE"))
            .build();
    var workerConfiguration =
        TranscodeWorkerConfiguration.builder()
            .workerId(uuid(environment, PREFIX + "ID"))
            .bootId(UUID.randomUUID())
            .availableSlots(positiveInteger(environment, PREFIX + "SLOTS", 1))
            .tlsIdentity(tlsIdentity)
            .sourceNamespaces(Map.of(sourceNamespaceId, path(environment, PREFIX + "SOURCE_ROOT")))
            .segmentBasePath(
                optionalPath(
                    environment,
                    PREFIX + "SEGMENT_BASE_PATH",
                    Path.of(System.getProperty("java.io.tmpdir"), "streamarr-worker-segments")))
            .build();
    return TranscodeWorkerSettings.builder()
        .controlPlaneHost(required(environment, PREFIX + "CONTROL_PLANE_HOST"))
        .controlPlanePort(port(environment, PREFIX + "CONTROL_PLANE_PORT", 9090))
        .ffmpegPath(optional(environment, PREFIX + "FFMPEG_PATH", "ffmpeg"))
        .workerConfiguration(workerConfiguration)
        .build();
  }

  private static String required(Map<String, String> environment, String key) {
    var value = environment.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(key + " is required");
    }
    return value;
  }

  private static String optional(Map<String, String> environment, String key, String defaultValue) {
    var value = environment.get(key);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private static Path path(Map<String, String> environment, String key) {
    return Path.of(required(environment, key));
  }

  private static Path optionalPath(Map<String, String> environment, String key, Path defaultValue) {
    return Path.of(optional(environment, key, defaultValue.toString()));
  }

  private static UUID uuid(Map<String, String> environment, String key) {
    var value = required(environment, key);
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(key + " must be a UUID", e);
    }
  }

  private static int positiveInteger(
      Map<String, String> environment, String key, int defaultValue) {
    var value = integer(environment, key, defaultValue);
    if (value < 1) {
      throw new IllegalArgumentException(key + " must be positive");
    }
    return value;
  }

  private static int port(Map<String, String> environment, String key, int defaultValue) {
    var value = positiveInteger(environment, key, defaultValue);
    if (value > 65_535) {
      throw new IllegalArgumentException(key + " must not exceed 65535");
    }
    return value;
  }

  private static int integer(Map<String, String> environment, String key, int defaultValue) {
    try {
      return Integer.parseInt(optional(environment, key, String.valueOf(defaultValue)));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " must be an integer", e);
    }
  }
}
