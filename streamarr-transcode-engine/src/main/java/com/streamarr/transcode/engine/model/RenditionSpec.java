package com.streamarr.transcode.engine.model;

import lombok.Builder;

@Builder
public record RenditionSpec(String label, int width, int height, long videoBitrate) {

  public RenditionSpec {
    requirePortableLabel(label);
    if (width < 1 || height < 1 || videoBitrate < 1 || videoBitrate > Long.MAX_VALUE / 2) {
      throw new IllegalArgumentException("Rendition dimensions and bitrate are invalid");
    }
  }

  public static void requirePortableLabel(String label) {
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
  }
}
