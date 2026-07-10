package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the session's remembered viewing context (active household/profile). Selection state lives
 * on auth_session, so refresh can restore scope without trusting the client's word.
 */
@Service
@RequiredArgsConstructor
public class SessionScopeService {

  private final HouseholdMembershipRepository membershipRepository;
  private final AccountProfileRepository accountProfileRepository;
  private final AuthSessionRepository sessionRepository;
  private final UserAccountRepository userAccountRepository;
  private final Clock clock;

  /**
   * ADR 0016 auto-selection: a sole household is selected automatically, then a sole selectable
   * profile within it.
   */
  @Transactional
  public TokenContext autoSelectContext(UserAccount account, AuthSession session) {
    var memberships = membershipRepository.findByAccountId(account.getId());
    if (memberships.size() != 1) {
      return TokenContext.builder().account(account).session(session).build();
    }

    var householdId = memberships.getFirst().getHouseholdId();
    session.setActiveHouseholdId(householdId);

    var links =
        accountProfileRepository.findByAccountIdAndHouseholdId(account.getId(), householdId);
    if (links.size() == 1) {
      session.setActiveProfileId(links.getFirst().getProfileId());
    }

    persistSelection(session);

    return TokenContext.builder()
        .account(account)
        .session(session)
        .householdId(householdId)
        .profileId(session.getActiveProfileId())
        .build();
  }

  /**
   * Revalidates the session's stored context for refresh (never the client's word). Profile scope
   * survives only when a single account_profile row exists for (account, stored household, stored
   * profile) — the composite FK makes that row imply membership AND profile-in-household. Otherwise
   * the scope downgrades to the highest valid one and the stale selection is cleared.
   */
  @Transactional
  public TokenContext revalidateStoredContext(UserAccount account, AuthSession session) {
    var householdId = session.getActiveHouseholdId();
    if (householdId == null) {
      clearSelection(session, true);
      return TokenContext.builder().account(account).session(session).build();
    }

    var profileId = session.getActiveProfileId();
    if (profileId != null) {
      var link =
          accountProfileRepository.findByAccountIdAndHouseholdIdAndProfileId(
              account.getId(), householdId, profileId);
      if (link.isPresent()) {
        return TokenContext.builder()
            .account(account)
            .session(session)
            .householdId(householdId)
            .profileId(profileId)
            .build();
      }
    }

    var membership =
        membershipRepository.findByAccountIdAndHouseholdId(account.getId(), householdId);
    if (membership.isPresent()) {
      clearSelection(session, false);
      return TokenContext.builder()
          .account(account)
          .session(session)
          .householdId(householdId)
          .build();
    }

    clearSelection(session, true);
    return TokenContext.builder().account(account).session(session).build();
  }

  /**
   * Selecting or switching a household always clears the active profile first, then may auto-select
   * the sole selectable profile in the new household — a mismatched household/profile pair can
   * never be minted.
   */
  @Transactional
  public TokenContext selectHousehold(UUID accountId, UUID sessionId, UUID householdId) {
    var account = loadAccount(accountId);
    var session = loadLiveSession(accountId, sessionId);

    membershipRepository
        .findByAccountIdAndHouseholdId(accountId, householdId)
        .orElseThrow(HouseholdAccessDeniedException::new);

    session.setActiveHouseholdId(householdId);
    session.setActiveProfileId(null);

    var links = accountProfileRepository.findByAccountIdAndHouseholdId(accountId, householdId);
    if (links.size() == 1) {
      session.setActiveProfileId(links.getFirst().getProfileId());
    }

    sessionRepository.save(session);

    return TokenContext.builder()
        .account(account)
        .session(session)
        .householdId(householdId)
        .profileId(session.getActiveProfileId())
        .build();
  }

  @Transactional
  public TokenContext selectProfile(UUID accountId, UUID sessionId, UUID profileId) {
    var account = loadAccount(accountId);
    var session = loadLiveSession(accountId, sessionId);

    var householdId = session.getActiveHouseholdId();
    if (householdId == null) {
      throw new HouseholdRequiredException();
    }

    accountProfileRepository
        .findByAccountIdAndHouseholdIdAndProfileId(accountId, householdId, profileId)
        .orElseThrow(ProfileAccessDeniedException::new);

    session.setActiveProfileId(profileId);
    sessionRepository.save(session);

    return TokenContext.builder()
        .account(account)
        .session(session)
        .householdId(householdId)
        .profileId(profileId)
        .build();
  }

  private UserAccount loadAccount(UUID accountId) {
    return userAccountRepository
        .findById(accountId)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  /**
   * A missing, foreign, or revoked session reads identically as unauthenticated (oracle-free). The
   * row is locked FOR UPDATE so a concurrent revoke cannot interleave between this read and the
   * selection's write: the revoke has either already committed (revokedAt set — rejected here) or
   * it blocks until this transaction commits and then applies on top. A plain read would let the
   * selection's blind JPA flush (AuthSession carries no @Version) overwrite a revocation that
   * committed in between, silently un-revoking the session and reviving its mintable version.
   */
  private AuthSession loadLiveSession(UUID accountId, UUID sessionId) {
    return sessionRepository
        .lockById(sessionId)
        .filter(session -> session.getAccountId().equals(accountId))
        .filter(session -> session.getRevokedAt() == null)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  private void clearSelection(AuthSession session, boolean includingHousehold) {
    var dirty = false;

    if (session.getActiveProfileId() != null) {
      session.setActiveProfileId(null);
      dirty = true;
    }
    if (includingHousehold && session.getActiveHouseholdId() != null) {
      session.setActiveHouseholdId(null);
      dirty = true;
    }

    if (dirty) {
      persistSelection(session);
    }
  }

  private void persistSelection(AuthSession session) {
    if (!sessionRepository.updateSelectionIfLive(session, clock.instant())) {
      throw new AuthenticationRequiredException();
    }
  }
}
