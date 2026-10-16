package com.streamarr.server.domain.streaming;

/** How a video stream's pixel format samples chroma. */
public enum ChromaSubsampling {
  CHROMA_420,
  CHROMA_422,
  CHROMA_444,
  /** The pixel format has no chroma planes, as in gray. */
  MONOCHROME
}
