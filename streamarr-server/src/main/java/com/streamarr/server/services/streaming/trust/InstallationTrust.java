package com.streamarr.server.services.streaming.trust;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class InstallationTrust {

  private final UUID installationId;
  private final byte[] bootstrapRootSha256;
  private final PublicTrustBundle activeBundle;

  public InstallationTrust(
      UUID installationId, byte[] bootstrapRootSha256, PublicTrustBundle activeBundle) {
    if (bootstrapRootSha256.length != 32) {
      throw new IllegalArgumentException("Installation trust root fingerprint must be 32 bytes");
    }
    this.installationId = Objects.requireNonNull(installationId);
    this.bootstrapRootSha256 = bootstrapRootSha256.clone();
    this.activeBundle = Objects.requireNonNull(activeBundle);
    if (!installationId.equals(activeBundle.installationId())) {
      throw new IllegalArgumentException(
          "Installation trust and active bundle must belong to the same installation");
    }
  }

  public UUID installationId() {
    return installationId;
  }

  public byte[] bootstrapRootSha256() {
    return bootstrapRootSha256.clone();
  }

  public PublicTrustBundle activeBundle() {
    return activeBundle;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof InstallationTrust that)) {
      return false;
    }
    return installationId.equals(that.installationId)
        && Arrays.equals(bootstrapRootSha256, that.bootstrapRootSha256)
        && activeBundle.equals(that.activeBundle);
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
