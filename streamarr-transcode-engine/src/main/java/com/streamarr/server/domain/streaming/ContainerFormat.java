package com.streamarr.server.domain.streaming;

import java.util.Set;

public enum ContainerFormat {
  MPEGTS(".ts", 3, Set.of("aac", "ac3", "eac3", "mp3")),
  FMP4(".m4s", 6, Set.of("aac", "ac3", "eac3", "mp3", "flac", "opus", "alac"));

  private final String segmentExtension;
  private final int hlsVersion;
  private final Set<String> supportedAudioCodecs;

  ContainerFormat(String segmentExtension, int hlsVersion, Set<String> supportedAudioCodecs) {
    this.segmentExtension = segmentExtension;
    this.hlsVersion = hlsVersion;
    this.supportedAudioCodecs = supportedAudioCodecs;
  }

  public String segmentExtension() {
    return segmentExtension;
  }

  public int hlsVersion() {
    return hlsVersion;
  }

  public Set<String> supportedAudioCodecs() {
    return supportedAudioCodecs;
  }
}
