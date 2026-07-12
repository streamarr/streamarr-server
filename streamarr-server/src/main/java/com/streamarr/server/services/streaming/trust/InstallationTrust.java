package com.streamarr.server.services.streaming.trust;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record InstallationTrust(
    UUID installationId, byte[] bootstrapRootSha256, PublicTrustBundle activeBundle) {

  public InstallationTrust {
    Objects.requireNonNull(bootstrapRootSha256);
    if (bootstrapRootSha256.length != 32) {
      throw new IllegalArgumentException("Installation trust root fingerprint must be 32 bytes");
    }
    Objects.requireNonNull(installationId);
    bootstrapRootSha256 = bootstrapRootSha256.clone();
    Objects.requireNonNull(activeBundle);
    if (!installationId.equals(activeBundle.installationId())) {
      throw new IllegalArgumentException(
          "Installation trust and active bundle must belong to the same installation");
    }
  }

  @Override
  public byte[] bootstrapRootSha256() {
    return bootstrapRootSha256.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other
        instanceof
        InstallationTrust(
            var thatInstallationId,
            var thatBootstrapRootSha256,
            var thatActiveBundle))) {
      return false;
    }
    return installationId.equals(thatInstallationId)
        && Arrays.equals(bootstrapRootSha256, thatBootstrapRootSha256)
        && activeBundle.equals(thatActiveBundle);
  }

  @Override
  public int hashCode() {
    return Objects.hash(installationId, Arrays.hashCode(bootstrapRootSha256), activeBundle);
  }

  @Override
  public String toString() {
    return "InstallationTrust[installationId="
        + installationId
        + ", activeBundleVersion="
        + activeBundle.version()
        + "]";
  }
}
