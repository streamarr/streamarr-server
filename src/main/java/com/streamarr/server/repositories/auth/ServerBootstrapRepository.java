package com.streamarr.server.repositories.auth;

import java.util.UUID;

public interface ServerBootstrapRepository {

  /**
   * Claims the one-time server bootstrap. Concurrent claims race on the singleton primary key, so
   * exactly one caller wins.
   */
  boolean claim(UUID adminAccountId);
}
