package com.streamarr.server.exceptions;

import java.util.UUID;

public class MediaFileNotFoundException extends RuntimeException {

  public MediaFileNotFoundException(UUID mediaFileId) {
    super("Media file not found: " + mediaFileId);
  }
}
