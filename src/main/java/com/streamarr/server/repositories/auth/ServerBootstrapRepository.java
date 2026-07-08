package com.streamarr.server.repositories.auth;

import java.util.UUID;

public interface ServerBootstrapRepository {

  /**
   * Claims the one-time server bootstrap. Concurrent claims race on the singleton primary key, so
   * exactly one caller wins.
   *
   * @return true if this call claimed the bootstrap; false if it was already claimed
   */
  boolean claim(UUID adminAccountId);

  boolean isClaimed();
}
