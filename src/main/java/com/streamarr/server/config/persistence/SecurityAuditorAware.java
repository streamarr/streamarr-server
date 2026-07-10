package com.streamarr.server.config.persistence;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Audit columns carry the authenticated account when a request identity exists; background threads
 * (scan pipeline, listeners) audit as empty — the columns are nullable by design.
 */
public class SecurityAuditorAware implements AuditorAware<UUID> {

  @Override
  public @NonNull Optional<UUID> getCurrentAuditor() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof StreamarrAuthenticationToken token) {
      return Optional.of(token.getPrincipal().accountId());
    }
    return Optional.empty();
  }
}
