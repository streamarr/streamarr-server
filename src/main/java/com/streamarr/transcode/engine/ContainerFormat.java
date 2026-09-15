package com.streamarr.transcode.engine;

public enum ContainerFormat {
  MPEGTS(".ts"),
  FMP4(".m4s");

  private final String segmentExtension;

  ContainerFormat(String segmentExtension) {
    this.segmentExtension = segmentExtension;
  }

  public String segmentExtension() {
    return segmentExtension;
  }
}
