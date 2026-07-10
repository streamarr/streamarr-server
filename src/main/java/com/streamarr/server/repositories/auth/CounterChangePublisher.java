package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.MembershipVersionChange;

public interface CounterChangePublisher {

  void publishMembership(MembershipVersionChange versionChange);
}
