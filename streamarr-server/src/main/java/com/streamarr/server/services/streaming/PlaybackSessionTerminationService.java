package com.streamarr.server.services.streaming;

import java.util.UUID;

public interface PlaybackSessionTerminationService {

  void destroy(UUID streamSessionId, UUID profileId);
}
