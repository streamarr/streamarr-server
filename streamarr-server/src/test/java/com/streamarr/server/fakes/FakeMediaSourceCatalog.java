package com.streamarr.server.fakes;

import com.streamarr.server.services.streaming.source.MediaSourceCatalog;
import com.streamarr.transcode.engine.model.MediaSourceRef;
import java.nio.file.Path;
import java.util.UUID;

public class FakeMediaSourceCatalog implements MediaSourceCatalog {

  @Override
  public MediaSourceRef referenceFor(UUID mediaFileId) {
    return new MediaSourceRef(mediaFileId, "media/source.mkv");
  }

  @Override
  public Path resolve(MediaSourceRef source) {
    return Path.of("/media").resolve(source.relativeKey());
  }
}
