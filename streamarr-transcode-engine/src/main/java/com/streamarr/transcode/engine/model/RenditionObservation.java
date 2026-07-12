package com.streamarr.transcode.engine.model;

public record RenditionObservation(String label, RenditionState state) {

  public RenditionObservation {
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("Rendition label must not be blank");
    }
    if (state == null) {
      throw new IllegalArgumentException("Rendition state must not be null");
    }
  }
}
