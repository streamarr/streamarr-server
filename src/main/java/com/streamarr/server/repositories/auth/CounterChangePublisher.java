package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.MembershipVersionChange;
import java.util.UUID;

public interface CounterChangePublisher {

  void publishSession(UUID sessionId, long version);

  void publishMembership(MembershipVersionChange versionChange);
}
