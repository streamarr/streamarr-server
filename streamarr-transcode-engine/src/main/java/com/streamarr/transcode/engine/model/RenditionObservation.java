package com.streamarr.transcode.engine.model;

public record RenditionObservation(String label, RenditionState state) {

  public RenditionObservation {
    RenditionSpec.requirePortableLabel(label);
    if (state == null) {
      throw new IllegalArgumentException("Rendition state must not be null");
    }
  }
}
