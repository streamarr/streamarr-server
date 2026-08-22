package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.services.auth.AuthenticatedIdentity;

public interface PlaybackAuthorityGate {

  boolean allows(AuthenticatedIdentity identity, PlaybackAuthority authority);
}
