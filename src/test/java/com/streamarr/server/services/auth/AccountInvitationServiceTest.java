package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationMode;
import com.streamarr.server.domain.auth.AccountInvitationReoffer;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.exceptions.InvitationNotAcceptableException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.FakeAccountInvitationReofferRepository;
import com.streamarr.server.fakes.FakeAccountInvitationRepository;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fakes.PlainPasswordEncoder;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.AccountInvitationService.AcceptInvitationCommand;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The principal-less invitation ceremonies over fakes: code resolution (throttled, constant failure
 * answer), the acceptance winner creating the whole identity shape atomically, and the first
 * Account of an empty Household becoming HouseholdAdmin.
 */
@Tag("UnitTest")
@DisplayName("Account Invitation Service Tests")
class AccountInvitationServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

  private final FakeAccountInvitationRepository invitations = new FakeAccountInvitationRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository();
  private final FakeProfileManagerRepository managers = new FakeProfileManagerRepository();
  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeAccountInvitationReofferRepository reoffers =
      new FakeAccountInvitationReofferRepository();
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeRefreshTokenRepository refreshTokens = new FakeRefreshTokenRepository();
  private final OpaqueOneTimeCodes opaqueCodes = new OpaqueOneTimeCodes();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final CredentialGuessThrottle throttle =
      new CredentialGuessThrottle(
          AuthThrottleProperties.builder().maxAttempts(5).window(Duration.ofMinutes(15)).build(),
          clock);

  private final AccountInvitationService service = serviceUsing(accounts);

  @Test
  @DisplayName("Should preview the decision details when an invitation code is presented")
  void shouldPreviewDecisionDetailsWhenInvitationCodeIsPresented() {
    var issued = pendingInvitation(pendingInvitationBuilder().build());

    var preview = service.lookup(issued.code());

    assertThat(preview.householdName()).isEqualTo("Home");
    assertThat(preview.profileName()).isEqualTo("Kai");
    assertThat(preview.householdRole()).isEqualTo(HouseholdRole.MEMBER);
  }

  @Test
  @DisplayName("Should create the whole identity shape when an invitation is accepted")
  void shouldCreateWholeIdentityShapeWhenInvitationIsAccepted() {
    // The Household already has a member, so the invited role is honored as-is.
    var localManager = accounts.save(AccountFixture.defaultAccountBuilder().build());
    var issued =
        pendingInvitation(
            pendingInvitationBuilder()
                .localManagerId(localManager.getId())
                .householdId(localManager.getHouseholdId())
                .build());

    var accepted =
        service.accept(
            AcceptInvitationCommand.builder()
                .code(issued.code())
                .displayName("Kai H")
                .password("a strong passphrase")
                .deviceName("web")
                .build());

    var account = accepted.account();
    assertThat(account.getEmail()).isEqualTo("kai@example.com");
    assertThat(account.getHouseholdRole()).isEqualTo(HouseholdRole.MEMBER);
    assertThat(account.getPasswordHash()).isNotEqualTo("a strong passphrase");
    var profile = profiles.findById(account.getPersonalProfileId()).orElseThrow();
    assertThat(profile.getName()).isEqualTo("Kai");
    assertThat(shares.isActivelyShared(profile.getId(), account.getHouseholdId())).isTrue();
    assertThat(managers.existsByAccountIdAndProfileId(localManager.getId(), profile.getId()))
        .isTrue();
    assertThat(accepted.rawRefreshToken()).isNotBlank();
    assertThat(invitations.findAll().getFirst().getStatus())
        .isEqualTo(AccountInvitationStatus.ACCEPTED);

    var consumed = issued.code();
    assertThatThrownBy(() -> service.lookup(consumed))
        .isInstanceOf(InvalidOneTimeCodeException.class);
  }

  @ParameterizedTest(name = "Should refuse acceptance when {0} fails at commit")
  @MethodSource("householdInvariantFailures")
  @DisplayName("Should refuse acceptance with a typed conflict when a Household invariant fails")
  void shouldRefuseAcceptanceWithTypedConflictWhenHouseholdInvariantFails(
      InvariantFailure failure) {
    var serviceWithViolatedInvariant =
        serviceUsing(new ConstraintViolatingUserAccountRepository(failure.constraint()));
    var issued = pendingInvitation(pendingInvitationBuilder().build());
    var command = acceptCommand(issued.code());

    assertThatThrownBy(() -> serviceWithViolatedInvariant.accept(command))
        .isInstanceOf(InvitationNotAcceptableException.class)
        .hasMessage(failure.message());
  }

  private static Stream<InvariantFailure> householdInvariantFailures() {
    return Stream.of(
        new InvariantFailure(
            "chk_household_profile_names_unique",
            "The Profile name is no longer available in the Household."),
        new InvariantFailure(
            "chk_profile_home_anchor", "The required Profile manager is no longer eligible."),
        new InvariantFailure(
            "chk_restricted_account_holds_no_authority",
            "A restricted Profile cannot hold Household authority."));
  }

  @Test
  @DisplayName("Should fail loudly when a pending invitation has no target Household")
  void shouldFailLoudlyWhenPendingInvitationHasNoTargetHousehold() {
    // V058 makes a PENDING row without a Household impossible; reaching it is corruption, not a
    // wrong code.
    var issued = pendingInvitation(pendingInvitationBuilder().householdId(null).build());
    var command = acceptCommand(issued.code());

    assertThatThrownBy(() -> service.accept(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(invitations.findAll().getFirst().getId().toString());
  }

  @Test
  @DisplayName("Should fail loudly when the Household guard row is missing at acceptance")
  void shouldFailLoudlyWhenHouseholdGuardRowIsMissingAtAcceptance() {
    var serviceWithoutGuardRow = serviceUsing(new GuardlessUserAccountRepository());
    var issued = pendingInvitation(pendingInvitationBuilder().build());
    var command = acceptCommand(issued.code());

    assertThatThrownBy(() -> serviceWithoutGuardRow.accept(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("household_guard");
  }

  @Test
  @DisplayName("Should make the first Account HouseholdAdmin when the Household is empty")
  void shouldMakeFirstAccountHouseholdAdminWhenHouseholdIsEmpty() {
    var issued = pendingInvitation(pendingInvitationBuilder().build());

    var accepted =
        service.accept(
            AcceptInvitationCommand.builder()
                .code(issued.code())
                .displayName("Kai H")
                .password("a strong passphrase")
                .deviceName("web")
                .build());

    assertThat(accepted.account().getHouseholdRole()).isEqualTo(HouseholdRole.ADMIN);
  }

  @Test
  @DisplayName("Should answer later presentations as invalid when an invitation is declined")
  void shouldAnswerLaterPresentationsAsInvalidWhenInvitationIsDeclined() {
    var issued = pendingInvitation(pendingInvitationBuilder().build());

    service.decline(issued.code());

    assertThat(invitations.findAll().getFirst().getStatus())
        .isEqualTo(AccountInvitationStatus.DECLINED);
    var consumed = issued.code();
    assertThatThrownBy(() -> service.decline(consumed))
        .isInstanceOf(InvalidOneTimeCodeException.class);
  }

  @Test
  @DisplayName("Should answer every miss the same way when an invitation code is invalid")
  void shouldAnswerEveryMissTheSameWayWhenInvitationCodeIsInvalid() {
    var issued = pendingInvitation(pendingInvitationBuilder().build());
    var expired = invitations.findAll().getFirst();
    var wrongSecret = expired.getPublicId() + ".not-the-secret";

    assertThatThrownBy(() -> service.lookup("not-even-a-code"))
        .isInstanceOf(InvalidOneTimeCodeException.class);
    assertThatThrownBy(() -> service.lookup("unknown.secret"))
        .isInstanceOf(InvalidOneTimeCodeException.class);
    assertThatThrownBy(() -> service.lookup(wrongSecret))
        .isInstanceOf(InvalidOneTimeCodeException.class);

    expired.setExpiresAt(NOW.minusSeconds(1));
    var expiredCode = issued.code();
    assertThatThrownBy(() -> service.lookup(expiredCode))
        .isInstanceOf(InvalidOneTimeCodeException.class);
  }

  @Test
  @DisplayName("Should throttle guesses when one public id is presented repeatedly")
  void shouldThrottleGuessesWhenOnePublicIdIsPresentedRepeatedly() {
    var issued = pendingInvitation(pendingInvitationBuilder().build());
    var publicId = invitations.findAll().getFirst().getPublicId();
    for (var attempt = 0; attempt < 5; attempt++) {
      var guess = publicId + ".guess-" + attempt;
      assertThatThrownBy(() -> service.lookup(guess))
          .isInstanceOf(InvalidOneTimeCodeException.class);
    }

    // The right code no longer helps: the budget is per publicId, not per outcome.
    var throttled = issued.code();
    assertThatThrownBy(() -> service.lookup(throttled))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName(
      "Should release the verification budget when the secret matches an expired invitation")
  void shouldReleaseVerificationBudgetWhenSecretMatchesExpiredInvitation() {
    var issued = pendingInvitation(pendingInvitationBuilder().build());
    var invitation = invitations.findAll().getFirst();
    invitation.setExpiresAt(NOW.minusSeconds(1));
    for (var attempt = 0; attempt < 4; attempt++) {
      var guess = invitation.getPublicId() + ".guess-" + attempt;
      assertThatThrownBy(() -> service.lookup(guess))
          .isInstanceOf(InvalidOneTimeCodeException.class);
    }

    // Possession of the secret is proven; the code's state is not a guess to be budgeted.
    var expiredCode = issued.code();
    assertThatThrownBy(() -> service.lookup(expiredCode))
        .isInstanceOf(InvalidOneTimeCodeException.class);

    var laterGuess = invitation.getPublicId() + ".guess-later";
    assertThatThrownBy(() -> service.lookup(laterGuess))
        .isInstanceOf(InvalidOneTimeCodeException.class);
  }

  @Test
  @DisplayName("Should not throttle when the correct invitation code is presented repeatedly")
  void shouldNotThrottleWhenCorrectInvitationCodeIsPresentedRepeatedly() {
    var issued = pendingInvitation(pendingInvitationBuilder().build());

    assertThatCode(
            () -> {
              for (var presentation = 0; presentation <= 5; presentation++) {
                service.lookup(issued.code());
              }
            })
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should not spend an opaque-code budget slot when unknown public ids are sprayed")
  void shouldNotSpendOpaqueCodeBudgetSlotWhenUnknownPublicIdsAreSprayed() {
    // A single slot: any key taken by the spray would refuse the valid code below.
    var singleSlotService = serviceUsing(accounts, throttleTracking(1));
    var issued = pendingInvitation(pendingInvitationBuilder().build());
    for (var key = 0; key < 3; key++) {
      var sprayed = "sprayed-public-id-" + key + ".secret";
      assertThatThrownBy(() -> singleSlotService.lookup(sprayed))
          .isInstanceOf(InvalidOneTimeCodeException.class);
    }

    assertThatCode(() -> singleSlotService.lookup(issued.code())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should refuse a valid code when every opaque-code budget slot is held")
  void shouldRefuseValidCodeWhenEveryOpaqueCodeBudgetSlotIsHeld() {
    // Slots belong to publicIds that exist, so only issued codes can fill them; the budget then
    // fails closed rather than growing without bound.
    var singleSlotService = serviceUsing(accounts, throttleTracking(1));
    var occupied = pendingInvitation(pendingInvitationBuilder().build());
    var refusedCode = pendingInvitation(pendingInvitationBuilder().build()).code();
    var wrongGuess = occupied.publicId() + ".not-the-secret";
    assertThatThrownBy(() -> singleSlotService.lookup(wrongGuess))
        .isInstanceOf(InvalidOneTimeCodeException.class);

    assertThatThrownBy(() -> singleSlotService.lookup(refusedCode))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  private CredentialGuessThrottle throttleTracking(int maximumOpaqueCodeBudgets) {
    return new CredentialGuessThrottle(
        AuthThrottleProperties.builder()
            .maxAttempts(5)
            .window(Duration.ofMinutes(15))
            .maxOpaqueCodeBudgets(maximumOpaqueCodeBudgets)
            .build(),
        clock);
  }

  private AccountInvitationService serviceUsing(FakeUserAccountRepository accountRepository) {
    return serviceUsing(accountRepository, throttle);
  }

  private AccountInvitationService serviceUsing(
      FakeUserAccountRepository accountRepository, CredentialGuessThrottle guessThrottle) {
    return new AccountInvitationService(
        invitations,
        accountRepository,
        profiles,
        managers,
        shares,
        reoffers,
        households,
        sessions,
        new RefreshTokenService(
            sessions,
            refreshTokens,
            AuthTokenProperties.builder()
                .accessTokenTtl(Duration.ofMinutes(10))
                .refreshTokenTtl(Duration.ofDays(30))
                .rotationGrace(Duration.ofSeconds(30))
                .build(),
            clock,
            new TokenReuseRevoker(new TokenReuseRevocationWriter(sessions, refreshTokens))),
        new OpaqueCodeResolver(opaqueCodes, guessThrottle, clock),
        new PlainPasswordEncoder(),
        new TransactionTemplate(new FakeTransactionManager()),
        new ConstraintViolationTranslator(),
        CredentialCodeProperties.builder()
            .invitationTtl(Duration.ofDays(7))
            .passwordResetTtl(Duration.ofHours(1))
            .replacementLockTimeout(Duration.ofSeconds(5))
            .build(),
        clock);
  }

  @Test
  @DisplayName("Should link the Profile and create its structural home share when CONNECT wins")
  void shouldLinkProfileAndCreateStructuralHomeShareWhenConnectWins() {
    var fixture = connectAcceptanceFixture();

    var accepted = service.accept(acceptCommand(fixture.code()));

    assertThat(accepted.account().getPersonalProfileId()).isEqualTo(fixture.profileId());
    assertThat(profiles.count()).isEqualTo(1);
    var homeShare =
        shares.findAll().stream()
            .filter(share -> share.getHouseholdId().equals(fixture.homeHouseholdId()))
            .findFirst()
            .orElseThrow();
    assertThat(homeShare.getStatus()).isEqualTo(ProfileShareStatus.ACTIVE);
    assertThat(homeShare.isStructural()).isTrue();
  }

  @Test
  @DisplayName("Should end the visit and clear its selection when CONNECT wins")
  void shouldEndVisitAndClearItsSelectionWhenConnectWins() {
    var fixture = connectAcceptanceFixture();

    service.accept(acceptCommand(fixture.code()));

    assertThat(shares.findById(fixture.visitId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.ENDED);
    assertThat(sessions.findById(fixture.watchingSessionId()).orElseThrow().getSelectedProfileId())
        .isNull();
  }

  @Test
  @DisplayName("Should invalidate the pending offer when CONNECT wins")
  void shouldInvalidatePendingOfferWhenConnectWins() {
    var fixture = connectAcceptanceFixture();

    service.accept(acceptCommand(fixture.code()));

    assertThat(shares.findById(fixture.pendingOfferId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should reoffer the recorded Household once when CONNECT wins")
  void shouldReofferRecordedHouseholdOnceWhenConnectWins() {
    var fixture = connectAcceptanceFixture();

    var accepted = service.accept(acceptCommand(fixture.code()));

    var reoffered =
        shares.findAll().stream()
            .filter(share -> share.getHouseholdId().equals(fixture.previousHouseholdId()))
            .filter(share -> share.getStatus() == ProfileShareStatus.PENDING)
            .toList();
    assertThat(reoffered).hasSize(1);
    assertThat(reoffered.getFirst().getOfferedByAccountId()).isEqualTo(accepted.account().getId());
    assertThat(reoffered.getFirst().getExpiresAt()).isAfter(NOW);
  }

  private ConnectAcceptanceFixture connectAcceptanceFixture() {
    var home = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var previous =
        households.save(HouseholdFixture.defaultHouseholdBuilder().name("Cabin").build());
    var third = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var orphan =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(home.getId()).name("Joe").build());
    accounts.save(AccountFixture.defaultAccountBuilder().householdId(home.getId()).build());
    shares.share(orphan.getId(), home.getId(), false);
    var visit = shares.share(orphan.getId(), previous.getId(), false);
    var pendingOffer =
        shares.save(
            ProfileHouseholdShare.builder()
                .profileId(orphan.getId())
                .householdId(third.getId())
                .status(ProfileShareStatus.PENDING)
                .expiresAt(NOW.plus(Duration.ofDays(7)))
                .build());
    var watching =
        sessions.save(
            AuthSession.builder()
                .accountId(UUID.randomUUID())
                .contextHouseholdId(previous.getId())
                .selectedProfileId(orphan.getId())
                .deviceName("tv")
                .build());
    var issued =
        pendingConnectInvitation(
            ConnectInvitationFixture.builder()
                .profileId(orphan.getId())
                .householdId(home.getId())
                .reofferHouseholdId(previous.getId())
                .build());

    return ConnectAcceptanceFixture.builder()
        .profileId(orphan.getId())
        .homeHouseholdId(home.getId())
        .previousHouseholdId(previous.getId())
        .visitId(visit.getId())
        .pendingOfferId(pendingOffer.getId())
        .watchingSessionId(watching.getId())
        .code(issued.code())
        .build();
  }

  @Test
  @DisplayName("Should invalidate rival CONNECT invitations for the Profile when one wins")
  void shouldInvalidateRivalConnectInvitationsForProfileWhenOneWins() {
    var home = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var orphan =
        profiles.save(ProfileFixture.defaultProfileBuilder().householdId(home.getId()).build());
    accounts.save(AccountFixture.defaultAccountBuilder().householdId(home.getId()).build());
    var fixture =
        ConnectInvitationFixture.builder()
            .profileId(orphan.getId())
            .householdId(home.getId())
            .build();
    var winner = pendingConnectInvitation(fixture);
    pendingConnectInvitation(fixture);

    service.accept(acceptCommand(winner.code()));

    var statuses = invitations.findAll().stream().map(AccountInvitation::getStatus).toList();
    assertThat(statuses)
        .containsExactlyInAnyOrder(
            AccountInvitationStatus.ACCEPTED, AccountInvitationStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should refuse a CONNECT acceptance when the Profile is gone or already linked")
  void shouldRefuseConnectAcceptanceWhenProfileGoneOrAlreadyLinked() {
    var home = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var orphan =
        profiles.save(ProfileFixture.defaultProfileBuilder().householdId(home.getId()).build());
    accounts.save(AccountFixture.defaultAccountBuilder().householdId(home.getId()).build());
    var issued =
        pendingConnectInvitation(
            ConnectInvitationFixture.builder()
                .profileId(orphan.getId())
                .householdId(home.getId())
                .build());
    accounts.save(AccountFixture.defaultAccountBuilder().personalProfileId(orphan.getId()).build());

    var linkedCommand = acceptCommand(issued.code());
    assertThatThrownBy(() -> service.accept(linkedCommand))
        .isInstanceOf(InvalidOneTimeCodeException.class);

    var vanished =
        pendingConnectInvitation(
            ConnectInvitationFixture.builder().householdId(home.getId()).build());
    var vanishedCode = vanished.code();
    assertThatThrownBy(() -> service.lookup(vanishedCode))
        .isInstanceOf(InvalidOneTimeCodeException.class);
    var vanishedCommand = acceptCommand(vanishedCode);
    assertThatThrownBy(() -> service.accept(vanishedCommand))
        .isInstanceOf(InvalidOneTimeCodeException.class);
  }

  @Test
  @DisplayName(
      "Should preview remaining managers, ending visits, and reoffers when looking up a CONNECT invitation")
  void shouldPreviewRemainingManagersEndingVisitsAndReoffersWhenLookingUpConnectInvitation() {
    var home = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var previous =
        households.save(HouseholdFixture.defaultHouseholdBuilder().name("Cabin").build());
    var orphan =
        profiles.save(ProfileFixture.defaultProfileBuilder().householdId(home.getId()).build());
    var manager =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(home.getId())
                .displayName("Nina")
                .build());
    managers.save(
        ProfileManager.builder().accountId(manager.getId()).profileId(orphan.getId()).build());
    shares.share(orphan.getId(), previous.getId(), false);
    var issued =
        pendingConnectInvitation(
            ConnectInvitationFixture.builder()
                .profileId(orphan.getId())
                .householdId(home.getId())
                .reofferHouseholdId(previous.getId())
                .build());

    var preview = service.lookup(issued.code());

    assertThat(preview.mode()).isEqualTo(AccountInvitationMode.CONNECT);
    assertThat(preview.remainingManagers()).containsExactly("Nina");
    assertThat(preview.endingHouseholds()).containsExactly("Cabin");
    assertThat(preview.reofferHouseholds()).containsExactly("Cabin");
  }

  private OpaqueOneTimeCodes.IssuedCode pendingConnectInvitation(ConnectInvitationFixture fixture) {
    var issued = opaqueCodes.issue();
    var invitation =
        invitations.save(
            AccountInvitation.builder()
                .recipientEmail("joe@example.com")
                .householdId(fixture.householdId())
                .householdName("Home")
                .householdRole(HouseholdRole.MEMBER)
                .mode(AccountInvitationMode.CONNECT)
                .profileId(fixture.profileId())
                .profileName("Joe")
                .profileKind(ProfileKind.ADULT)
                .issuerAccountId(UUID.randomUUID())
                .expiresAt(NOW.plus(Duration.ofDays(7)))
                .publicId(issued.publicId())
                .secretDigest(issued.digest())
                .build());
    if (fixture.reofferHouseholdId() != null) {
      reoffers.save(
          AccountInvitationReoffer.builder()
              .invitationId(invitation.getId())
              .householdId(fixture.reofferHouseholdId())
              .householdName("Cabin")
              .build());
    }
    return issued;
  }

  @Builder
  private record ConnectInvitationFixture(
      UUID profileId, UUID householdId, UUID reofferHouseholdId) {}

  @Builder
  private record ConnectAcceptanceFixture(
      UUID profileId,
      UUID homeHouseholdId,
      UUID previousHouseholdId,
      UUID visitId,
      UUID pendingOfferId,
      UUID watchingSessionId,
      String code) {}

  private static AcceptInvitationCommand acceptCommand(String code) {
    return AcceptInvitationCommand.builder()
        .code(code)
        .displayName("Kai H")
        .password("a strong passphrase")
        .deviceName("web")
        .build();
  }

  private PendingInvitation.PendingInvitationBuilder pendingInvitationBuilder() {
    return PendingInvitation.builder().role(HouseholdRole.MEMBER).householdId(UUID.randomUUID());
  }

  private OpaqueOneTimeCodes.IssuedCode pendingInvitation(PendingInvitation invitation) {
    var issued = opaqueCodes.issue();
    invitations.save(
        AccountInvitation.builder()
            .recipientEmail("kai@example.com")
            .householdId(invitation.householdId())
            .householdName("Home")
            .householdRole(invitation.role())
            .profileName("Kai")
            .profileKind(ProfileKind.ADULT)
            .localManagerAccountId(invitation.localManagerId())
            .issuerAccountId(UUID.randomUUID())
            .expiresAt(NOW.plus(Duration.ofDays(7)))
            .publicId(issued.publicId())
            .secretDigest(issued.digest())
            .build());
    return issued;
  }

  @Builder
  private record PendingInvitation(HouseholdRole role, UUID localManagerId, UUID householdId) {}

  private record InvariantFailure(String constraint, String message) {
    @Override
    public String toString() {
      return constraint;
    }
  }

  /** Answers as if the Household's coordination row had vanished while its FK still resolves. */
  private static final class GuardlessUserAccountRepository extends FakeUserAccountRepository {

    @Override
    public Optional<HouseholdRole> roleForNewAccount(
        UUID householdId, HouseholdRole requestedRole) {
      return Optional.empty();
    }
  }

  /** Fails the Account write the way a deferred constraint trigger fails at commit. */
  private static final class ConstraintViolatingUserAccountRepository
      extends FakeUserAccountRepository {

    private final String constraint;

    private ConstraintViolatingUserAccountRepository(String constraint) {
      this.constraint = constraint;
    }

    @Override
    public <S extends UserAccount> S saveAndFlush(S entity) {
      throw new DataIntegrityViolationException(
          "could not execute statement",
          new ConstraintViolationException(
              "violates " + constraint, new SQLException("23514"), constraint));
    }
  }
}
