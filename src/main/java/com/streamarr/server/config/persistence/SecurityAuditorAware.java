package com.streamarr.server.config.persistence;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.services.audit.SystemWork;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Supplies the auditor for every audit column that JPA or jOOQ writes.
 *
 * <p>System work names the {@link SystemActor}, even when a request started the work on its own
 * thread. A signed-in request names its account. Any other write leaves the auditor empty, because
 * a request without an account is still a person's request.
 */
public class SecurityAuditorAware implements AuditorAware<UUID> {

  @Override
  public @NonNull Optional<UUID> getCurrentAuditor() {
    if (SystemWork.isRunningAsSystemActor()) {
      return Optional.of(SystemActor.ID);
    }

    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof StreamarrAuthenticationToken token) {
      return Optional.of(token.getPrincipal().accountId());
    }

    return Optional.empty();
  }
}
