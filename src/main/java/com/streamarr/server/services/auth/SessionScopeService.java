package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.UnwrittenAuthSessionException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the session's optional active portable profile. */
@Service
@RequiredArgsConstructor
public class SessionScopeService {

  private final ProfileAvailabilityService profileAvailabilityService;
  private final AuthSessionRepository sessionRepository;
  private final UserAccountRepository userAccountRepository;
  private final SecurityAuditService auditService;
  private final ProfileEntryAuthorizer profileEntryAuthorizer;
  private final ProfileSelectionPersistenceService profileSelectionPersistenceService;
  private final Clock clock;

  /**
   * Revalidates the session's active profile and clears it when the profile is no longer selectable.
   *
   * @param account the account associated with the session
   * @param session the authentication session whose active profile is revalidated
   * @return a token context containing the account and session, with the active profile ID when it remains selectable
   */
  @Transactional
  public TokenContext revalidateStoredContext(UserAccount account, AuthSession session) {
    var profileId = session.getActiveProfileId();
    if (profileId == null) {
      return TokenContext.builder().account(account).session(session).build();
    }

    try {
      profileAvailabilityService.requireSelectableProfile(account.getId(), profileId);
      return TokenContext.builder().account(account).session(session).profileId(profileId).build();
    } catch (ProfileAccessDeniedException _) {
      var clearedSession = session.toBuilder().activeProfileId(null).build();
      persistSelection(clearedSession);
      auditSelectionClear(account.getId(), profileId);
      return TokenContext.builder().account(account).session(clearedSession).build();
    }
  }

  /**
   * Selects an active profile for an authentication session after validating access and profile entry authorization.
   *
   * @param profilePin the PIN used to authorize entry into the profile
   * @return a token context containing the account, updated session, and selected profile
   */
  public TokenContext selectProfile(
      UUID accountId, UUID sessionId, UUID profileId, String profilePin) {
    var account = loadAccount(accountId);
    requireLiveSession(accountId, sessionId);
    var profile = profileAvailabilityService.requireSelectableProfile(accountId, profileId);
    profileEntryAuthorizer.requireEntry(accountId, profile, profilePin);

    var session = profileSelectionPersistenceService.select(accountId, sessionId, profileId);

    return TokenContext.builder().account(account).session(session).profileId(profileId).build();
  }

  /**
   * Loads the account associated with the specified identifier.
   *
   * @param accountId the account identifier
   * @return the matching user account
   * @throws AuthenticationRequiredException if no account exists for the identifier
   */
  private UserAccount loadAccount(UUID accountId) {
    return userAccountRepository
        .findById(accountId)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  /**
   * Verifies that the session belongs to the account and has not been revoked.
   *
   * @param accountId the account identifier
   * @param sessionId the session identifier
   * @throws AuthenticationRequiredException if the session does not exist, belongs to another account, or has been revoked
   */
  private void requireLiveSession(UUID accountId, UUID sessionId) {
    sessionRepository
        .findById(sessionId)
        .filter(session -> session.getAccountId().equals(accountId))
        .filter(session -> session.getRevokedAt() == null)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  /**
   * Persists the session's profile selection while the session remains active.
   *
   * @param session the session whose profile selection is persisted
   * @throws AuthenticationRequiredException if the session exists but is no longer active
   * @throws UnwrittenAuthSessionException if the session does not exist
   */
  private void persistSelection(AuthSession session) {
    if (sessionRepository.updateSelectionIfLive(session, clock.instant())) {
      return;
    }

    if (sessionRepository.hasRow(session.getId())) {
      throw new AuthenticationRequiredException();
    }

    throw new UnwrittenAuthSessionException(session.getId());
  }

  /**
   * Records an audit event for clearing an unavailable stored profile selection.
   *
   * @param accountId the account whose session selection was cleared
   * @param profileId the unavailable profile that was selected
   */
  private void auditSelectionClear(UUID accountId, UUID profileId) {
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(accountId)
            .targetAccountId(accountId)
            .targetProfileId(profileId)
            .operation(SecurityAuditOperation.PROFILE_SELECTION_CLEARED)
            .reason("Stored profile selection is no longer available")
            .build());
  }
}
