package com.streamarr.server.services.streaming.source;

import com.streamarr.transcode.engine.model.MediaSourceRef;
import java.nio.file.Path;
import java.util.UUID;

public interface MediaSourceCatalog {

  MediaSourceRef referenceFor(UUID mediaFileId);

  Path resolve(MediaSourceRef source);
}
