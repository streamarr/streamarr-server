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
    @NonNull Optional<UUID> profileId,
    @NonNull Optional<Instant> reauthenticatedAt) {

  @SuppressWarnings("java:S1068") // Lombok builder defaults — fields are used by generated code
  public static class TokenContextBuilder {
    private Optional<UUID> profileId = Optional.empty();
    private Optional<Instant> reauthenticatedAt = Optional.empty();
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
        .profileId(Optional.ofNullable(session.getSelectedProfileId()))
        .build();
  }

  public TokenContext withReauthenticatedAt(@NonNull Instant instant) {
    return withReauthenticatedAt(Optional.of(instant));
  }

  public TokenContext withReauthenticatedAt(@NonNull Optional<Instant> instant) {
    return new TokenContext(account, session, contextHouseholdId, profileId, instant);
  }

  public TokenScope scope() {
    return profileId.isEmpty() ? TokenScope.ACCOUNT : TokenScope.PROFILE;
  }
}
