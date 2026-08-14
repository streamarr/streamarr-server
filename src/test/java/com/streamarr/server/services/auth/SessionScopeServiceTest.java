package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.UnwrittenAuthSessionException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

@Tag("UnitTest")
@DisplayName("Session Scope Service Tests")
class SessionScopeServiceTest {

  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeProfileHouseholdShareRepository shareRepository =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeSecurityAuditEventRepository auditRepository =
      new FakeSecurityAuditEventRepository();
  private final ProfileAvailabilityService availabilityService =
      new ProfileAvailabilityService(accountRepository, shareRepository, profileRepository);
  private final SessionScopeService service =
      new SessionScopeService(
          availabilityService,
          sessionRepository,
          accountRepository,
          new SecurityAuditService(auditRepository),
          new ProfilePinService(NoOpPasswordEncoder.getInstance()),
          Clock.systemUTC());

  @Test
  @DisplayName("Should select profile shared into account home")
  void shouldSelectProfileSharedIntoAccountHomeWithoutHouseholdSelection() {
    var homeHouseholdId = UUID.randomUUID();
    var account = saveAccount(homeHouseholdId);
    var profile = profileRepository.save(Profile.builder().name("Portable Profile").build());
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(homeHouseholdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());

    var context = service.selectProfile(account.getId(), session.getId(), profile.getId(), null);

    assertThat(context.profileId()).isEqualTo(profile.getId());
    assertThat(sessionRepository.findById(session.getId()).orElseThrow().getActiveProfileId())
        .isEqualTo(profile.getId());
  }

  @Test
  @DisplayName("Should keep account scope when stored session has no profile selection")
  void shouldKeepAccountScopeWhenStoredSessionHasNoProfileSelection() {
    var account = saveAccount(UUID.randomUUID());
    var session = sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());

    var context = service.revalidateStoredContext(account, session);

    assertThat(context.account()).isEqualTo(account);
    assertThat(context.session()).isEqualTo(session);
    assertThat(context.profileId()).isNull();
  }

  @Test
  @DisplayName("Should keep selectable stored profile in session context")
  void shouldKeepSelectableStoredProfileInSessionContext() {
    var householdId = UUID.randomUUID();
    var account = saveAccount(householdId);
    var profile = profileRepository.save(Profile.builder().name("Portable Profile").build());
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var session =
        sessionRepository.save(
            AuthSession.builder()
                .accountId(account.getId())
                .activeProfileId(profile.getId())
                .build());

    var context = service.revalidateStoredContext(account, session);

    assertThat(context.profileId()).isEqualTo(profile.getId());
    assertThat(session.getActiveProfileId()).isEqualTo(profile.getId());
  }

  @Test
  @DisplayName("Should clear stored profile when share is no longer selectable")
  void shouldClearStoredProfileWhenShareIsNoLongerSelectable() {
    var account = saveAccount(UUID.randomUUID());
    var session =
        sessionRepository.save(
            AuthSession.builder()
                .accountId(account.getId())
                .activeProfileId(UUID.randomUUID())
                .build());

    var context = service.revalidateStoredContext(account, session);

    assertThat(context.profileId()).isNull();
    assertThat(sessionRepository.findById(session.getId()).orElseThrow().getActiveProfileId())
        .isNull();
  }

  @Test
  @DisplayName("Should fail closed when invalid selection belongs to revoked stored session")
  void shouldFailClosedWhenInvalidSelectionBelongsToRevokedStoredSession() {
    var account = saveAccount(UUID.randomUUID());
    var session =
        sessionRepository.save(
            AuthSession.builder()
                .accountId(account.getId())
                .activeProfileId(UUID.randomUUID())
                .revokedAt(Instant.EPOCH)
                .build());

    assertThatThrownBy(() -> service.revalidateStoredContext(account, session))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should distinguish unwritten session when invalid selection cannot be persisted")
  void shouldDistinguishUnwrittenSessionWhenInvalidSelectionCannotBePersisted() {
    var account = saveAccount(UUID.randomUUID());
    var session =
        sessionRepository.save(
            AuthSession.builder()
                .accountId(account.getId())
                .activeProfileId(UUID.randomUUID())
                .build());
    sessionRepository.delete(session);

    assertThatThrownBy(() -> service.revalidateStoredContext(account, session))
        .isInstanceOf(UnwrittenAuthSessionException.class);
  }

  @Test
  @DisplayName("Should reject selection when account session or profile is unavailable")
  void shouldRejectSelectionWhenAccountSessionOrProfileIsUnavailable() {
    var missingAccountId = UUID.randomUUID();
    var missingAccountSessionId = UUID.randomUUID();
    var missingAccountProfileId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                service.selectProfile(
                    missingAccountId, missingAccountSessionId, missingAccountProfileId, null))
        .isInstanceOf(AuthenticationRequiredException.class);

    var account = saveAccount(UUID.randomUUID());
    var otherAccountSession =
        sessionRepository.save(AuthSession.builder().accountId(UUID.randomUUID()).build());
    var accountId = account.getId();
    var otherAccountSessionId = otherAccountSession.getId();
    var otherAccountProfileId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                service.selectProfile(
                    accountId, otherAccountSessionId, otherAccountProfileId, null))
        .isInstanceOf(AuthenticationRequiredException.class);

    var revokedSession =
        sessionRepository.save(
            AuthSession.builder().accountId(account.getId()).revokedAt(Instant.EPOCH).build());
    var revokedSessionId = revokedSession.getId();
    var revokedSessionProfileId = UUID.randomUUID();
    assertThatThrownBy(
            () -> service.selectProfile(accountId, revokedSessionId, revokedSessionProfileId, null))
        .isInstanceOf(AuthenticationRequiredException.class);

    var liveSession =
        sessionRepository.save(AuthSession.builder().accountId(account.getId()).build());
    var liveSessionId = liveSession.getId();
    var unavailableProfileId = UUID.randomUUID();
    assertThatThrownBy(
            () -> service.selectProfile(accountId, liveSessionId, unavailableProfileId, null))
        .isInstanceOf(ProfileAccessDeniedException.class);
    assertThat(liveSession.getActiveProfileId()).isNull();
  }

  private UserAccount saveAccount(UUID homeHouseholdId) {
    return accountRepository.save(
        UserAccount.builder()
            .email("viewer-" + UUID.randomUUID() + "@example.com")
            .displayName("Viewer")
            .passwordHash("{noop}not-a-real-hash")
            .accountRole(AccountRole.USER)
            .homeHouseholdId(homeHouseholdId)
            .householdRole(HouseholdRole.MEMBER)
            .build());
  }
}
