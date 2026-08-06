package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;

public class FakePlaybackAuthorityGate implements PlaybackAuthorityGate {

  private boolean allowed = true;
  private RuntimeException failure;
  private Runnable duringNextCheck;

  @Override
  public boolean allows(PlaybackAuthority authority) {
    if (duringNextCheck != null) {
      var action = duringNextCheck;
      duringNextCheck = null;
      action.run();
    }
    if (failure != null) {
      throw failure;
    }
    return allowed;
  }

  /**
   * Runs {@code action} inside the next authority check. Callers use this to land an interleaving
   * precisely between a caller's registry read and its write-back.
   */
  public void onNextCheck(Runnable action) {
    duringNextCheck = action;
  }

  public void deny() {
    allowed = false;
  }

  public void failWith(RuntimeException failure) {
    this.failure = failure;
  }
}
