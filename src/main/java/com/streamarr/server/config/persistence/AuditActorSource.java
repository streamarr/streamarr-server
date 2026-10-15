package com.streamarr.server.config.persistence;

import com.streamarr.server.domain.audit.AuditActor;
import java.util.Optional;

/** Resolves the actor of the work running on the calling thread. */
@FunctionalInterface
public interface AuditActorSource {

  /** Returns the calling thread's actor, or empty when the thread names none. */
  Optional<AuditActor> currentActor();
}
