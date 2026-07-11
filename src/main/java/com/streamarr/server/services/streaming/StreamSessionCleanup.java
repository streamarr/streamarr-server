package com.streamarr.server.services.streaming;

import java.util.UUID;

public interface StreamSessionCleanup {

  void cleanup(UUID streamSessionId);
}
