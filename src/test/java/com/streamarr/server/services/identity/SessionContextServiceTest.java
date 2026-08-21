package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.UnwrittenAuthSessionException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.TokenScope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Session Context Service Tests")
class SessionContextServiceTest {

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final LiveSessions liveSessions = new LiveSessions(accounts, sessions);
  private final SessionContextService service =
      new SessionContextService(liveSessions, accounts, profiles, sessions, new MutableClock());
  private final HouseholdContextService households =
      new HouseholdContextService(liveSessions, accounts, service);

  private UserAccount account;
  private Profile personal;
  private UUID visitedHouseholdId;

  @BeforeEach
  void setUp() {
    account = accounts.save(AccountFixture.defaultAccountBuilder().build());
    personal =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .id(account.getPersonalProfileId())
                .householdId(account.getHouseholdId())
                .build());
    shares.share(personal.getId(), account.getHouseholdId(), true);
    visitedHouseholdId = UUID.randomUUID();
  }

  @Test
  @DisplayName("Should keep a still-valid context and selection when revalidating")
  void shouldKeepStillValidContextAndSelectionWhenRevalidating() {
    var session = session(account.getHouseholdId(), personal.getId());

    var context = service.revalidateStoredContext(account, session);

    assertThat(context.contextHouseholdId()).isEqualTo(account.getHouseholdId());
    assertThat(context.profileId()).isEqualTo(personal.getId());
    assertThat(context.scope()).isEqualTo(TokenScope.PROFILE);
  }

  @Test
  @DisplayName("Should fall back to the membership Household picker when the visit ended")
  void shouldFallBackToMembershipHouseholdPickerWhenVisitEnded() {
    var session = session(visitedHouseholdId, personal.getId());

    var context = service.revalidateStoredContext(account, session);

    assertThat(context.contextHouseholdId()).isEqualTo(account.getHouseholdId());
    assertThat(context.profileId()).isNull();
    var stored = sessions.findById(session.getId()).orElseThrow();
    assertThat(stored.getContextHouseholdId()).isEqualTo(account.getHouseholdId());
    assertThat(stored.getSelectedProfileId()).isNull();
  }

  @Test
  @DisplayName("Should keep the visited Household when the Personal Profile share is active")
  void shouldKeepVisitedHouseholdWhenPersonalProfileShareIsActive() {
    shares.share(personal.getId(), visitedHouseholdId, false);
    var session = session(visitedHouseholdId, personal.getId());

    var context = service.revalidateStoredContext(account, session);

    assertThat(context.contextHouseholdId()).isEqualTo(visitedHouseholdId);
    assertThat(context.profileId()).isEqualTo(personal.getId());
  }

  @Test
  @DisplayName("Should clear the selected Profile when it is unavailable in the context")
  void shouldClearSelectedProfileWhenUnavailableInContext() {
    var gone = UUID.randomUUID();
    var session = session(account.getHouseholdId(), gone);

    var context = service.revalidateStoredContext(account, session);

    assertThat(context.profileId()).isNull();
    assertThat(sessions.findById(session.getId()).orElseThrow().getSelectedProfileId()).isNull();
  }

  @Test
  @DisplayName("Should clear the selected Profile when the safety rule locks it")
  void shouldClearSelectedProfileWhenSafetyRuleLocksIt() {
    var kid =
        profiles.save(
            ProfileFixture.kidProfileBuilder().householdId(account.getHouseholdId()).build());
    shares.share(kid.getId(), account.getHouseholdId(), false);
    var session = session(account.getHouseholdId(), personal.getId());

    var context = service.revalidateStoredContext(account, session);

    assertThat(context.profileId()).isNull();
    assertThat(context.scope()).isEqualTo(TokenScope.ACCOUNT);
  }

  @Test
  @DisplayName("Should clear the selection when switching to a usable Household")
  void shouldClearSelectionWhenSwitchingToUsableHousehold() {
    shares.share(personal.getId(), visitedHouseholdId, false);
    var session = session(account.getHouseholdId(), personal.getId());

    var context = households.selectHousehold(account.getId(), session.getId(), visitedHouseholdId);

    assertThat(context.contextHouseholdId()).isEqualTo(visitedHouseholdId);
    assertThat(context.profileId()).isNull();
    assertThat(context.scope()).isEqualTo(TokenScope.ACCOUNT);
  }

  @Test
  @DisplayName("Should deny the switch when the Account may not use the Household")
  void shouldDenySwitchWhenAccountMayNotUseHousehold() {
    var session = session(account.getHouseholdId(), null);
    var accountId = account.getId();
    var sessionId = session.getId();

    assertThatThrownBy(() -> households.selectHousehold(accountId, sessionId, visitedHouseholdId))
        .isInstanceOf(HouseholdAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should read a revoked or foreign session as unauthenticated when switching")
  void shouldReadRevokedOrForeignSessionAsUnauthenticatedWhenSwitching() {
    var session = session(account.getHouseholdId(), null);
    sessions.revoke(session.getId(), SessionRevocationReason.LOGOUT, Instant.now());
    var accountId = account.getId();
    var sessionId = session.getId();
    var membershipHouseholdId = account.getHouseholdId();

    assertThatThrownBy(
            () -> households.selectHousehold(accountId, sessionId, membershipHouseholdId))
        .isInstanceOf(AuthenticationRequiredException.class);

    var foreign = sessions.save(AuthSession.builder().accountId(UUID.randomUUID()).build());
    assertThatThrownBy(
            () ->
                households.selectHousehold(
                    account.getId(), foreign.getId(), account.getHouseholdId()))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should report an unwritten session when it was never persisted")
  void shouldReportUnwrittenSessionWhenItWasNeverPersisted() {
    var unwritten = AuthSession.builder().id(UUID.randomUUID()).accountId(account.getId()).build();

    assertThatThrownBy(() -> service.revalidateStoredContext(account, unwritten))
        .isInstanceOf(UnwrittenAuthSessionException.class);
  }

  private AuthSession session(UUID contextHouseholdId, UUID selectedProfileId) {
    return sessions.save(
        AuthSession.builder()
            .accountId(account.getId())
            .contextHouseholdId(contextHouseholdId)
            .selectedProfileId(selectedProfileId)
            .build());
  }
}
