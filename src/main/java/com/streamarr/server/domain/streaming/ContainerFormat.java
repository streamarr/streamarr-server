package com.streamarr.server.domain.streaming;

public enum ContainerFormat {
  MPEGTS(".ts", 3),
  FMP4(".m4s", 6);

  private final String segmentExtension;
  private final int hlsVersion;

  ContainerFormat(String segmentExtension, int hlsVersion) {
    this.segmentExtension = segmentExtension;
    this.hlsVersion = hlsVersion;
  }

  public String segmentExtension() {
    return segmentExtension;
  }

  public int hlsVersion() {
    return hlsVersion;
  }
}
