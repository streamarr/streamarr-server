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
  MP4("mp4", "video/mp4"),
  MOV("mov", "video/quicktime");

  /** The extension of the direct-play URL, which players use to pick a parser. */
  private final String extension;

  private final String mediaType;
}
