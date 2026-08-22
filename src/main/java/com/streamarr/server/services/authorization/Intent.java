package com.streamarr.server.services.authorization;

import java.util.UUID;

/**
 * What a caller wants to do, in domain terms. The authorization module maps an intent to the Cedar
 * action, resource, required facts, and attempt context itself; callers never choose an action,
 * assemble entities, or supply their own reading of authority. {@code T} is the value an allowed
 * decision carries back — normalized values the mutation must then write, or {@link
 * AuthorizationUnit} when there is nothing to return.
 */
// java:S2326: T is the type witness for what an allowed decision returns; AuthorizationService
// signatures consume it even though no member of the interface does.
@SuppressWarnings("java:S2326")
public sealed interface Intent<T> {

  /** Register a new library; a whole-surface gate on server administration. */
  record AddLibrary() implements Intent<AuthorizationUnit> {}

  record RemoveLibrary(UUID libraryId) implements Intent<AuthorizationUnit> {}

  record ScanLibrary(UUID libraryId) implements Intent<AuthorizationUnit> {}

  record RefreshLibrary(UUID libraryId) implements Intent<AuthorizationUnit> {}
}
