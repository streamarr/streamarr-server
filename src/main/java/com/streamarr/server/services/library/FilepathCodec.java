package com.streamarr.server.services.library;

import java.nio.file.Path;

/** Compatibility facade for Java migrations compiled against the codec's original package. */
public final class FilepathCodec {

  private FilepathCodec() {}

  public static String encode(Path path) {
    return com.streamarr.server.services.filepath.FilepathCodec.encode(path);
  }
}
