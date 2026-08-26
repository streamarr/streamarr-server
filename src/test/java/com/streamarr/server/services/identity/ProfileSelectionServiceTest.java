package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.InvalidProfilePinException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.ProfileLockedException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fakes.PlainPasswordEncoder;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.ProfilePinVerifier;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Profile Selection Service Tests")
class ProfileSelectionServiceTest {

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final PasswordEncoder encoder = new PlainPasswordEncoder();
  private final MutableClock clock = new MutableClock();
  private final FakeCredentialAttemptRepository credentialAttempts =
      new FakeCredentialAttemptRepository();
  private final LiveSessions liveSessions = new LiveSessions(accounts, sessions);
  private final SessionContextService sessionContext =
      new SessionContextService(
          liveSessions, accounts, profiles, sessions, shares, new MutableClock());

  private UserAccount account;
  private AuthSession session;
  private Profile personal;
  private FakeAuthorizationService authorization;
  private ProfileSelectionService service;

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
    session =
        sessions.save(
            AuthSession.builder()
                .accountId(account.getId())
                .contextHouseholdId(account.getHouseholdId())
                .build());
    authorization = new FakeAuthorizationService(identity());
    service =
        new ProfileSelectionService(
            profiles,
            new ProfilePinVerifier(encoder, credentialAttempts.gate(clock)),
            authorization,
            sessionContext);
  }

  @Test
  @DisplayName("Should record the selection and return profile context when allowed without a PIN")
  void shouldRecordSelectionAndReturnProfileContextWhenAllowedWithoutPin() {
    var context = service.selectProfile(identity(), command(personal.getId(), null));

    assertThat(context.profileId()).contains(personal.getId());
    assertThat(context.scope()).isEqualTo(TokenScope.PROFILE);
    assertThat(sessions.findById(session.getId()).orElseThrow().getSelectedProfileId())
        .isEqualTo(personal.getId());
    assertThat(authorization.recordedIntents())
        .containsExactly(new Intent.SelectProfile(personal.getId(), false));
    assertThat(credentialAttempts.attempts()).isEmpty();
  }

  @Test
  @DisplayName("Should reject selection when the live session moved to another Household")
  void shouldRejectSelectionWhenLiveSessionMovedToAnotherHousehold() {
    var staleIdentity = identity();
    session.setContextHouseholdId(UUID.randomUUID());
    sessions.save(session);
    var command = command(personal.getId(), null);

    assertThatThrownBy(() -> service.selectProfile(staleIdentity, command))
        .isInstanceOf(ProfileAccessDeniedException.class);
    assertThat(sessions.findById(session.getId()).orElseThrow().getSelectedProfileId()).isNull();
  }

  @Test
  @DisplayName("Should refuse selection when the Profile is unavailable in the context Household")
  void shouldRefuseSelectionWhenProfileIsUnavailableInContextHousehold() {
    var elsewhere = profiles.save(ProfileFixture.defaultProfileBuilder().build());

    var identity = identity();
    var command = command(elsewhere.getId(), null);

    assertThatThrownBy(() -> service.selectProfile(identity, command))
        .isInstanceOf(ProfileAccessDeniedException.class);
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should require the PIN when the Profile has one")
  void shouldRequirePinWhenProfileHasOne() {
    pin(personal, "4242");

    var identity = identity();
    var command = command(personal.getId(), null);

    assertThatThrownBy(() -> service.selectProfile(identity, command))
        .isInstanceOf(InvalidProfilePinException.class);
    assertThat(sessions.findById(session.getId()).orElseThrow().getSelectedProfileId()).isNull();
    assertThat(credentialAttempts.attempts()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse the selection when the journal blocks the PIN attempt")
  void shouldRefuseSelectionWhenJournalBlocksPinAttempt() {
    pin(personal, "4242");
    credentialAttempts.rejectReservations(Duration.ofMinutes(15));

    var identity = identity();
    var command = command(personal.getId(), "4242");

    assertThatThrownBy(() -> service.selectProfile(identity, command))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should select the Profile when the PIN is verified")
  void shouldSelectProfileWhenPinIsVerified() {
    pin(personal, "4242");

    var context = service.selectProfile(identity(), command(personal.getId(), "4242"));

    assertThat(context.profileId()).contains(personal.getId());
    assertThat(authorization.recordedIntents())
        .containsExactly(new Intent.SelectProfile(personal.getId(), true));
  }

  @Test
  @DisplayName("Should report the lock when Cedar denies a Profile the safety rule locks")
  void shouldReportLockWhenCedarDeniesProfileSafetyRuleLocks() {
    var kid =
        profiles.save(
            ProfileFixture.kidProfileBuilder().householdId(account.getHouseholdId()).build());
    shares.share(kid.getId(), account.getHouseholdId(), false);
    authorization.denyAll();

    // The unpinned Adult is locked because a Kid is available.
    var identity = identity();
    var command = command(personal.getId(), null);

    assertThatThrownBy(() -> service.selectProfile(identity, command))
        .isInstanceOf(ProfileLockedException.class);
  }

  @Test
  @DisplayName("Should report access denied when Cedar denies for any other reason")
  void shouldReportAccessDeniedWhenCedarDeniesForAnyOtherReason() {
    authorization.denyAll();

    var identity = identity();
    var command = command(personal.getId(), null);

    assertThatThrownBy(() -> service.selectProfile(identity, command))
        .isInstanceOf(ProfileAccessDeniedException.class);
  }

  @Test
  @DisplayName("Should fail closed when no decision could be made")
  void shouldFailClosedWhenNoDecisionCouldBeMade() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);

    var identity = identity();
    var command = command(personal.getId(), null);

    assertThatThrownBy(() -> service.selectProfile(identity, command))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should read a revoked session as unauthenticated when recording the selection")
  void shouldReadRevokedSessionAsUnauthenticatedWhenRecordingSelection() {
    sessions.revoke(session.getId(), SessionRevocationReason.LOGOUT, Instant.now());

    var identity = identity();
    var command = command(personal.getId(), null);

    assertThatThrownBy(() -> service.selectProfile(identity, command))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  private void pin(Profile profile, String pin) {
    profile.setPinHash(encoder.encode(pin));
    profiles.save(profile);
  }

  private SelectProfileCommand command(UUID profileId, String pin) {
    return SelectProfileCommand.builder()
        .profileId(profileId)
        .pin(pin)
        .ipAddress("192.0.2.24")
        .build();
  }

  private AuthenticatedIdentity identity() {
    return AuthenticatedIdentity.builder()
        .accountId(account.getId())
        .authSessionId(session.getId())
        .scope(TokenScope.ACCOUNT)
        .householdId(account.getHouseholdId())
        .householdRole(account.getHouseholdRole())
        .contextHouseholdId(account.getHouseholdId())
        .build();
  }
  @Test
  @DisplayName("Should refuse the correct PIN when five wrong PINs precede it")
  void shouldRefuseCorrectPinWhenFiveWrongPinsPrecedeIt() {
    pin(personal, "4242");
    for (var attempt = 0; attempt < 5; attempt++) {
      var identity = identity();
      var wrong = command(personal.getId(), "000" + attempt);
      assertThatThrownBy(() -> service.selectProfile(identity, wrong))
          .isInstanceOf(InvalidProfilePinException.class);
    }
    var identity = identity();
    var correct = command(personal.getId(), "4242");

    assertThatThrownBy(() -> service.selectProfile(identity, correct))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
    assertThat(sessions.findById(session.getId()).orElseThrow().getSelectedProfileId()).isNull();
    assertThat(credentialAttempts.attempts())
        .hasSize(5)
        .allSatisfy(
            attempt ->
                assertThat(attempt.target())
                    .isEqualTo(
                        CredentialAttemptTarget.builder()
                            .kind(CredentialKind.PROFILE_PIN)
                            .accountId(account.getId())
                            .profileId(personal.getId())
                            .ipAddress("192.0.2.24")
                            .build()));
  }
}
