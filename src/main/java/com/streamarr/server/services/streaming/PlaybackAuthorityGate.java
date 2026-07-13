package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.PlaybackAuthority;

public interface PlaybackAuthorityGate {

  boolean allows(PlaybackAuthority authority);
}
