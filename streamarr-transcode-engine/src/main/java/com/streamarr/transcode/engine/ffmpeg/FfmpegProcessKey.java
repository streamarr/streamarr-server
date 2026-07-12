package com.streamarr.transcode.engine.ffmpeg;

import com.streamarr.transcode.engine.model.RenditionSpec;
import com.streamarr.transcode.engine.model.TranscodeJobRef;

public record FfmpegProcessKey(TranscodeJobRef jobRef, String renditionLabel) {

  public FfmpegProcessKey {
    if (jobRef == null) {
      throw new IllegalArgumentException("Transcode job reference is required");
    }
    RenditionSpec.requirePortableLabel(renditionLabel);
  }
}
