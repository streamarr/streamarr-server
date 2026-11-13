package com.streamarr.server.services.streaming;

/** The audio operations that a playback session permits after a copied audio stream ran short. */
public enum AudioFormatConstraint {
  COPY_OR_ENCODE,
  ENCODE_ONLY
}
