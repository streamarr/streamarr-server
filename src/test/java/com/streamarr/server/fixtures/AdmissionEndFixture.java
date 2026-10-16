package com.streamarr.server.fixtures;

import com.streamarr.server.services.library.AdmissionEnd;

public final class AdmissionEndFixture {

  private AdmissionEndFixture() {}

  /** The end of an execution that is neither stopped nor drained. */
  public static AdmissionEnd neverReached() {
    return () -> false;
  }
}
