package com.streamarr.server.domain.streaming;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The container of a media file as the probe found it. Direct play serves the file with this
 * container's media type, never one guessed from the file name.
 */
@Getter
@RequiredArgsConstructor
public enum SourceContainer {
  /** MPEG-4 Part 14, including M4V. */
  MP4("mp4", "video/mp4"),
  /** The QuickTime file format. */
  MOV("mov", "video/quicktime"),
  /** Matroska, including WebM. */
  MATROSKA("mkv", "video/x-matroska"),
  /** The MPEG-2 transport stream, including M2TS. */
  MPEG_TS("ts", "video/mp2t"),
  /** The MPEG-2 program stream, as in MPG and VOB files. */
  MPEG_PS("mpg", "video/mpeg"),
  /** Audio Video Interleave. */
  AVI("avi", "video/x-msvideo"),
  /** The Advanced Systems Format, as in WMV files. */
  ASF("asf", "video/x-ms-asf"),
  /** Flash Video. */
  FLV("flv", "video/x-flv");

  /** The extension of the direct-play URL, which players use to pick a parser. */
  private final String extension;

  private final String mediaType;
}
