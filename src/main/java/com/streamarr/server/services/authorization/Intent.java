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

  /** See the Profiles available in the session's context Household. */
  record ViewProfilePicker() implements Intent<AuthorizationUnit> {}

  /**
   * Select a Profile in the context Household. {@code pinVerified} is trusted attempt context
   * created only by the selection service after the throttled PIN check — never by a client.
   */
  record SelectProfile(UUID profileId, boolean pinVerified) implements Intent<AuthorizationUnit> {}

  /** Read a Profile's viewing history, progress, and preferences. */
  record ViewProfileActivity(UUID profileId) implements Intent<AuthorizationUnit> {}

  record ViewHouseholdAdministration(UUID householdId) implements Intent<AuthorizationUnit> {}

  /** The server-wide Household catalogue; ServerAdmin reads all application-domain data. */
  record ViewHouseholds() implements Intent<AuthorizationUnit> {}

  record ViewAccountAdministration(UUID accountId) implements Intent<AuthorizationUnit> {}

  record ViewProfileAdministration(UUID profileId) implements Intent<AuthorizationUnit> {}

  /** The live playback decision for the selected Profile in the context Household (ADR 0018). */
  record Playback() implements Intent<AuthorizationUnit> {}

  /** Grant server-wide authority; requiresFreshReauthentication (ADR 0024 §ServerAdmin). */
  record GrantServerAdmin(UUID accountId) implements Intent<AuthorizationUnit> {}

  /** Revoke server-wide authority; requiresFreshReauthentication (ADR 0024 §ServerAdmin). */
  record RevokeServerAdmin(UUID accountId) implements Intent<AuthorizationUnit> {}

  /** Create an empty Household; live ServerAdmin work. */
  record CreateHousehold() implements Intent<AuthorizationUnit> {}

  /** Edit a Household's settings — that Household's HouseholdAdmin (live role) or ServerAdmin. */
  record RenameHousehold(UUID householdId) implements Intent<AuthorizationUnit> {}

  /** Edit an Account's administrative display name — that Account itself or ServerAdmin. */
  record RenameAccount(UUID accountId) implements Intent<AuthorizationUnit> {}

  /** Household role changes are ServerAdmin work (ADR 0024 §ServerAdmin). */
  record GrantHouseholdAdmin(UUID accountId) implements Intent<AuthorizationUnit> {}

  record RevokeHouseholdAdmin(UUID accountId) implements Intent<AuthorizationUnit> {}

  record DisableAccount(UUID accountId) implements Intent<AuthorizationUnit> {}

  record EnableAccount(UUID accountId) implements Intent<AuthorizationUnit> {}
}
