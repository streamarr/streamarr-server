package com.streamarr.server.services.authorization;

import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.time.Instant;
import java.util.UUID;

/**
 * The one authorization entry point (ADR 0015, ADR 0025): request identity is read here, and every
 * point decision is made here by submitting a typed {@link Intent}. Auditing reads the
 * SecurityContext separately for audit columns. Resolvers and controllers resolve identity through
 * this facade on the request thread and pass explicit ids down — services and batch loaders never
 * touch the SecurityContext.
 */
public interface AuthorizationService {

  AuthenticatedIdentity currentIdentity();

  /**
   * The raw value of the signature-verified token that authenticated this request. Sourced from the
   * validated token, never from a request parameter, so callers that echo it (e.g. playlist segment
   * URLs) reflect only server-validated input.
   */
  String currentTokenValue();

  /**
   * Expiry of the signature-verified token that authenticated this request. Sourced from the
   * validated token, never reparsed from the raw value, so derived credentials (scope-change
   * tokens) cap their lifetime against trusted input. Never null: the strict expiry validator
   * rejects tokens without an exp claim before they authenticate.
   */
  Instant currentTokenExpiry();

  UUID requireAccountId();

  /** The context Household this session is using; every non-playback token carries one. */
  UUID requireHousehold();

  UUID requireProfile();

  /**
   * Decides a resource operation whose expected denial belongs in the mutation's payload user
   * errors. Callers map {@code Denied} to a typed domain rejection and {@code Failed} to a
   * sanitized {@code AUTHORIZATION_UNAVAILABLE} error.
   */
  Decision<AuthorizationUnit> decide(AuthenticatedIdentity identity, Intent.UnitIntent intent);

  Decision<ProfilePolicyTransition> decide(
      AuthenticatedIdentity identity, Intent.ProfilePolicyChange intent);

  /**
   * Gates a whole surface: returns the intent's value when allowed, throws {@link
   * org.springframework.security.access.AccessDeniedException} (top-level FORBIDDEN) when denied,
   * and {@link com.streamarr.server.exceptions.AuthorizationUnavailableException} when no decision
   * could be made.
   */
  AuthorizationUnit requireAllowed(AuthenticatedIdentity identity, Intent.UnitIntent intent);

  /**
   * A pure yes/no for visibility checks that shape an answer (hide versus forbid) rather than gate
   * it: denied is false, and an engine that could not decide fails closed as unavailable.
   */
  default boolean isAllowed(AuthenticatedIdentity identity, Intent.UnitIntent intent) {
    return switch (decide(identity, intent)) {
      case Decision.Allowed<?> _ -> true;
      case Decision.Denied<?> _ -> false;
      case Decision.Failed<?> _ -> throw new AuthorizationUnavailableException();
    };
  }
}
