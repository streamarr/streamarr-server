package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.services.streaming.PlaybackAuthorityGate;

public class FakePlaybackAuthorityGate implements PlaybackAuthorityGate {

  private boolean allowed = true;
  private int checkCount;

  @Override
  public boolean allows(PlaybackAuthority authority) {
    checkCount++;
    return allowed;
  }

  public void deny() {
    allowed = false;
  }

  public void resetCheckCount() {
    checkCount = 0;
  }

  public int checkCount() {
    return checkCount;
  }
}
