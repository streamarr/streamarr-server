package com.streamarr.server.fakes;

import com.streamarr.server.repositories.auth.ServerBootstrapRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class FakeServerBootstrapRepository implements ServerBootstrapRepository {

  private final AtomicReference<UUID> claimedBy = new AtomicReference<>();

  @Override
  public boolean claim(UUID adminAccountId) {
    return claimedBy.compareAndSet(null, adminAccountId);
  }
}
