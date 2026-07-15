package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;

public class FakePlaybackAuthorityGate implements PlaybackAuthorityGate {

  private boolean allowed = true;
  private int checkCount;
  private RuntimeException failure;

  @Override
  public boolean allows(PlaybackAuthority authority) {
    checkCount++;
    if (failure != null) {
      throw failure;
    }
    return allowed;
  }

  public void deny() {
    allowed = false;
  }

  public void failWith(RuntimeException failure) {
    this.failure = failure;
  }

  public void resetCheckCount() {
    checkCount = 0;
  }

  public int checkCount() {
    return checkCount;
  }
}
