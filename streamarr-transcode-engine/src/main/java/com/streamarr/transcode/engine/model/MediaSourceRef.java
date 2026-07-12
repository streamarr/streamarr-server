package com.streamarr.transcode.engine.model;

import java.util.UUID;

public record MediaSourceRef(UUID namespaceId, String relativeKey) {

  public MediaSourceRef {
    if (namespaceId == null) {
      throw new IllegalArgumentException("Source namespace is required");
    }
    if (!isPortable(relativeKey)) {
      throw new IllegalArgumentException("Source key must be a normalized portable relative path");
    }
  }

  private static boolean isPortable(String key) {
    if (key == null || key.isEmpty() || key.indexOf('\\') >= 0 || key.indexOf('\0') >= 0) {
      return false;
    }
    if (key.charAt(0) == '/' || hasWindowsDrivePrefix(key)) {
      return false;
    }
    for (var segment : key.split("/", -1)) {
      if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasWindowsDrivePrefix(String key) {
    return key.length() >= 2 && Character.isLetter(key.charAt(0)) && key.charAt(1) == ':';
  }
}
