package com.streamarr.server.services.streaming.trust;

public enum WorkerCertificateProfile {
  V1((short) 1);

  private final short version;

  WorkerCertificateProfile(short version) {
    this.version = version;
  }

  public static WorkerCertificateProfile fromVersion(short version) {
    if (version == V1.version) {
      return V1;
    }
    throw new IllegalArgumentException("Unsupported worker certificate profile version");
  }

  public short version() {
    return version;
  }
}
