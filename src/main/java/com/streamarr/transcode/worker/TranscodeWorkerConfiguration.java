package com.streamarr.transcode.worker;

import com.streamarr.transcode.tls.PemTlsIdentity;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record TranscodeWorkerConfiguration(
    UUID workerId,
    UUID bootId,
    PemTlsIdentity tlsIdentity,
    Map<UUID, Path> sourceNamespaces,
    Path segmentBasePath) {

  public TranscodeWorkerConfiguration {
    Objects.requireNonNull(workerId);
    Objects.requireNonNull(bootId);
    Objects.requireNonNull(tlsIdentity);
    sourceNamespaces = Map.copyOf(sourceNamespaces);
    Objects.requireNonNull(segmentBasePath);
  }
}
