package com.streamarr.server.fakes;

import com.streamarr.server.repositories.auth.VersionCounterReader;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FakeVersionCounterReader implements VersionCounterReader {

  public final Map<UUID, Long> sessionVersions = new ConcurrentHashMap<>();
  public final Map<String, Long> membershipVersions = new ConcurrentHashMap<>();
  public final Map<UUID, Long> profilePolicyVersions = new ConcurrentHashMap<>();

  private RuntimeException failure;

  public void failWith(RuntimeException exception) {
    this.failure = exception;
  }

  @Override
  public Optional<Long> sessionVersion(UUID sessionId) {
    throwIfFailing();
    return Optional.ofNullable(sessionVersions.get(sessionId));
  }

  @Override
  public Optional<Long> membershipVersion(UUID accountId, UUID householdId) {
    throwIfFailing();
    return Optional.ofNullable(membershipVersions.get(accountId + ":" + householdId));
  }

  @Override
  public Optional<Long> profilePolicyVersion(UUID profileId) {
    throwIfFailing();
    return Optional.ofNullable(profilePolicyVersions.get(profileId));
  }

  private void throwIfFailing() {
    if (failure != null) {
      throw failure;
    }
  }
}
