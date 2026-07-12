package com.streamarr.server.services.streaming;

import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class TranscodeCapacityTracker {

  private enum Phase {
    STARTING,
    ACTIVE
  }

  public record ActiveClaim(UUID sessionId, long epoch) {}

  private record Claim(int slots, Phase phase, long epoch) {}

  private final HashMap<UUID, Claim> claims = new HashMap<>();
  private long nextEpoch;

  public synchronized int claimUpTo(UUID sessionId, int requestedSlots, int maximumSlots) {
    validate(requestedSlots, maximumSlots);
    var existing = claims.get(sessionId);
    if (existing != null) {
      return existing.slots();
    }

    var admitted = Math.min(requestedSlots, available(maximumSlots));
    if (admitted <= 0) {
      return 0;
    }
    claims.put(sessionId, new Claim(admitted, Phase.STARTING, nextEpoch()));
    return admitted;
  }

  public synchronized boolean claimExact(UUID sessionId, int slots, int maximumSlots) {
    validate(slots, maximumSlots);
    var existing = claims.get(sessionId);
    if (existing != null) {
      if (existing.slots() != slots) {
        return false;
      }
      claims.put(sessionId, new Claim(slots, Phase.STARTING, nextEpoch()));
      return true;
    }
    if (available(maximumSlots) < slots) {
      return false;
    }
    claims.put(sessionId, new Claim(slots, Phase.STARTING, nextEpoch()));
    return true;
  }

  public synchronized void markActive(UUID sessionId) {
    claims.computeIfPresent(
        sessionId, (_, claim) -> new Claim(claim.slots(), Phase.ACTIVE, claim.epoch()));
  }

  public synchronized void release(UUID sessionId) {
    claims.remove(sessionId);
  }

  public synchronized void releaseActive(ActiveClaim expected) {
    var current = claims.get(expected.sessionId());
    if (current == null || current.phase() != Phase.ACTIVE || current.epoch() != expected.epoch()) {
      return;
    }
    claims.remove(expected.sessionId());
  }

  public synchronized Optional<ActiveClaim> activeClaim(UUID sessionId) {
    var claim = claims.get(sessionId);
    if (claim == null || claim.phase() != Phase.ACTIVE) {
      return Optional.empty();
    }
    return Optional.of(new ActiveClaim(sessionId, claim.epoch()));
  }

  public synchronized Set<ActiveClaim> activeClaims() {
    return Set.copyOf(
        claims.entrySet().stream()
            .filter(entry -> entry.getValue().phase() == Phase.ACTIVE)
            .map(entry -> new ActiveClaim(entry.getKey(), entry.getValue().epoch()))
            .toList());
  }

  private long nextEpoch() {
    nextEpoch = Math.incrementExact(nextEpoch);
    return nextEpoch;
  }

  private int available(int maximumSlots) {
    return maximumSlots - claims.values().stream().mapToInt(Claim::slots).sum();
  }

  private static void validate(int requestedSlots, int maximumSlots) {
    if (requestedSlots <= 0 || maximumSlots <= 0) {
      throw new IllegalArgumentException("Transcode capacity values must be positive");
    }
  }
}
