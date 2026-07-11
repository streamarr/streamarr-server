package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.Optional;
import java.util.UUID;

public interface PlaybackSessionAccessService {

  Optional<StreamSession> access(UUID requestedSessionId, AuthenticatedIdentity identity);
}
