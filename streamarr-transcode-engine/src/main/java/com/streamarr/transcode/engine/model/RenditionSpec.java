package com.streamarr.transcode.engine.model;

import lombok.Builder;

@Builder
public record RenditionSpec(String label, int width, int height, long videoBitrate) {

  public RenditionSpec {
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("Rendition label is required");
    }
    if (label.equals(".")
        || label.equals("..")
        || label.indexOf('/') >= 0
        || label.indexOf('\\') >= 0
        || label.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Rendition label must be one portable path segment");
    }
    if (width < 1 || height < 1 || videoBitrate < 1) {
      throw new IllegalArgumentException("Rendition dimensions and bitrate must be positive");
    }
  }
}
