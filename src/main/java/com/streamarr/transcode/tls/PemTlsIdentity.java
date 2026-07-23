package com.streamarr.transcode.tls;

import java.nio.file.Path;
import java.util.Objects;
import lombok.Builder;

@Builder
public record PemTlsIdentity(Path certificate, Path privateKey, Path trustBundle) {

  public PemTlsIdentity {
    Objects.requireNonNull(certificate, "certificate is required");
    Objects.requireNonNull(privateKey, "privateKey is required");
    Objects.requireNonNull(trustBundle, "trustBundle is required");
  }
}
