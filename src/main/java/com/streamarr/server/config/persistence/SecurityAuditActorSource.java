package com.streamarr.server.config.persistence;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.audit.AuditActor;
import com.streamarr.server.services.audit.SystemWork;
import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * System work names the System actor, even when a request started the work on its own thread. A
 * signed-in request names its account. A request without an account is a person's request, so it
 * never names the System actor. A thread that is neither system work nor a request names no actor.
 */
public class SecurityAuditActorSource implements AuditActorSource {

  @Override
  public Optional<AuditActor> currentActor() {
    if (SystemWork.isRunningAsSystemActor()) {
      return Optional.of(new AuditActor.System());
    }

    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof StreamarrAuthenticationToken token) {
      return Optional.of(new AuditActor.Account(token.getPrincipal().accountId()));
    }

    if (authentication instanceof AnonymousAuthenticationToken) {
      return Optional.of(new AuditActor.RequestWithoutAccount());
    }

    return Optional.empty();
  }
}
