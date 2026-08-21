package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.UserAccount;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * What a token is minted from: the Account and its membership Household and role, its session, the
 * context Household the session is using, and the selected Profile when one is. The identity
 * services validate the context before building this; the issuer only mints.
 */
@Builder
public record TokenContext(
    @NonNull UserAccount account,
    @NonNull AuthSession session,
    @NonNull UUID contextHouseholdId,
    UUID profileId,
    Optional<Instant> reauthenticatedAt) {

  public TokenContext {
    if (reauthenticatedAt == null) {
      reauthenticatedAt = Optional.empty();
    }
  }

  /** The session's remembered context: membership Household unless the session switched. */
  public static TokenContext of(UserAccount account, AuthSession session) {
    return TokenContext.builder()
        .account(account)
        .session(session)
        .contextHouseholdId(
            session.getContextHouseholdId() == null
                ? account.getHouseholdId()
                : session.getContextHouseholdId())
        .profileId(session.getSelectedProfileId())
        .build();
  }

  /** The same context stamped with the ceremony instant its source token carried, if any. */
  public TokenContext withReauthenticatedAt(Instant instant) {
    return withReauthenticatedAt(Optional.ofNullable(instant));
  }

  public TokenContext withReauthenticatedAt(Optional<Instant> instant) {
    return new TokenContext(account, session, contextHouseholdId, profileId, instant);
  }

  public TokenScope scope() {
    return profileId == null ? TokenScope.ACCOUNT : TokenScope.PROFILE;
  }
}
