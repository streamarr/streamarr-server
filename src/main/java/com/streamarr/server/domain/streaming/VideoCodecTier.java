package com.streamarr.server.domain.streaming;

/** The tier of an AV1 or H.265 video stream. H.264 has no tier. An encode forces only MAIN. */
public enum VideoCodecTier {
  MAIN,
  HIGH
}
