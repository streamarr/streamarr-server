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
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.FakeAccountInvitationReofferRepository;
import com.streamarr.server.fakes.FakeAccountInvitationRepository;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.AccountInvitationCeremonyService.AcceptInvitationCommand;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The principal-less invitation ceremonies over fakes: code resolution (throttled, constant failure
 * answer), the acceptance winner creating the whole identity shape atomically, and the first
 * Account of an empty Household becoming HouseholdAdmin.
 */
@Tag("UnitTest")
@DisplayName("Account Invitation Ceremony Service Tests")
class AccountInvitationCeremonyServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

  private final FakeAccountInvitationRepository invitations = new FakeAccountInvitationRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository();
  private final FakeProfileManagerRepository managers = new FakeProfileManagerRepository();
  private final FakeProfileManagerInvitationRepository managerInvitations =
      new FakeProfileManagerInvitationRepository();
  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeAccountInvitationReofferRepository reoffers =
      new FakeAccountInvitationReofferRepository();
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeRefreshTokenRepository refreshTokens = new FakeRefreshTokenRepository();
  private final OpaqueCodes opaqueCodes = new OpaqueCodes();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final CredentialGuessThrottle throttle =
      new CredentialGuessThrottle(
          AuthThrottleProperties.builder().maxAttempts(5).window(Duration.ofMinutes(15)).build(),
          clock);

  private final AccountInvitationCeremonyService service =
      new AccountInvitationCeremonyService(
          invitations,
          accounts,
          profiles,
          managers,
          managerInvitations,
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
          opaqueCodes,
          throttle,
          new PlainEncoder(),
          new TransactionTemplate(new FakeTransactionManager()),
          new ConstraintViolationTranslator(),
          new CredentialCodeProperties(null, null),
          clock);

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
  @DisplayName("Should end every visit and clear only visiting selections when CONNECT wins")
  void shouldEndEveryVisitAndClearOnlyVisitingSelectionsWhenConnectWins() {
    var fixture = connectAcceptanceFixture();

    service.accept(acceptCommand(fixture.code()));

    assertThat(fixture.visitIds())
        .allSatisfy(
            visitId ->
                assertThat(shares.findById(visitId).orElseThrow().getStatus())
                    .isEqualTo(ProfileShareStatus.ENDED));
    assertThat(fixture.visitingSessionIds())
        .allSatisfy(
            sessionId ->
                assertThat(sessions.findById(sessionId).orElseThrow().getSelectedProfileId())
                    .isNull());
    assertThat(sessions.findById(fixture.homeSessionId()).orElseThrow().getSelectedProfileId())
        .isEqualTo(fixture.profileId());
  }

  @Test
  @DisplayName("Should invalidate every pending offer when CONNECT wins")
  void shouldInvalidateEveryPendingOfferWhenConnectWins() {
    var fixture = connectAcceptanceFixture();

    service.accept(acceptCommand(fixture.code()));

    assertThat(fixture.pendingOfferIds())
        .allSatisfy(
            offerId ->
                assertThat(shares.findById(offerId).orElseThrow().getStatus())
                    .isEqualTo(ProfileShareStatus.INVALIDATED));
  }

  @Test
  @DisplayName("Should reoffer every recorded Household once when CONNECT wins")
  void shouldReofferEveryRecordedHouseholdOnceWhenConnectWins() {
    var fixture = connectAcceptanceFixture();

    var accepted = service.accept(acceptCommand(fixture.code()));

    var reoffered =
        shares.findAll().stream()
            .filter(share -> fixture.reofferHouseholdIds().contains(share.getHouseholdId()))
            .filter(share -> share.getStatus() == ProfileShareStatus.PENDING)
            .toList();
    assertThat(reoffered)
        .extracting(ProfileHouseholdShare::getHouseholdId)
        .containsExactlyInAnyOrderElementsOf(fixture.reofferHouseholdIds());
    assertThat(reoffered)
        .allSatisfy(
            share -> {
              assertThat(share.getOfferedByAccountId()).isEqualTo(accepted.account().getId());
              assertThat(share.getExpiresAt()).isAfter(NOW);
            });
  }

  private ConnectAcceptanceFixture connectAcceptanceFixture() {
    var home = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var previous =
        households.save(HouseholdFixture.defaultHouseholdBuilder().name("Cabin").build());
    var otherPrevious =
        households.save(HouseholdFixture.defaultHouseholdBuilder().name("Lodge").build());
    var third = households.save(HouseholdFixture.defaultHouseholdBuilder().name("Third").build());
    var fourth = households.save(HouseholdFixture.defaultHouseholdBuilder().name("Fourth").build());
    var orphan =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(home.getId()).name("Joe").build());
    accounts.save(AccountFixture.defaultAccountBuilder().householdId(home.getId()).build());
    shares.share(orphan.getId(), home.getId(), false);
    var firstVisit = shares.share(orphan.getId(), previous.getId(), false);
    var secondVisit = shares.share(orphan.getId(), otherPrevious.getId(), false);
    var firstPendingOffer =
        shares.save(
            ProfileHouseholdShare.builder()
                .profileId(orphan.getId())
                .householdId(third.getId())
                .status(ProfileShareStatus.PENDING)
                .expiresAt(NOW.plus(Duration.ofDays(7)))
                .build());
    var secondPendingOffer =
        shares.save(
            ProfileHouseholdShare.builder()
                .profileId(orphan.getId())
                .householdId(fourth.getId())
                .status(ProfileShareStatus.PENDING)
                .expiresAt(NOW.plus(Duration.ofDays(7)))
                .build());
    var firstWatching =
        sessions.save(
            AuthSession.builder()
                .accountId(UUID.randomUUID())
                .contextHouseholdId(previous.getId())
                .selectedProfileId(orphan.getId())
                .deviceName("tv")
                .build());
    var secondWatching =
        sessions.save(
            AuthSession.builder()
                .accountId(UUID.randomUUID())
                .contextHouseholdId(otherPrevious.getId())
                .selectedProfileId(orphan.getId())
                .deviceName("tablet")
                .build());
    var homeWatching =
        sessions.save(
            AuthSession.builder()
                .accountId(UUID.randomUUID())
                .contextHouseholdId(home.getId())
                .selectedProfileId(orphan.getId())
                .deviceName("phone")
                .build());
    var issued =
        pendingConnectInvitation(
            ConnectInvitationFixture.builder()
                .profileId(orphan.getId())
                .householdId(home.getId())
                .reoffers(
                    List.of(
                        ReofferHouseholdFixture.builder()
                            .householdId(previous.getId())
                            .householdName(previous.getName())
                            .build(),
                        ReofferHouseholdFixture.builder()
                            .householdId(otherPrevious.getId())
                            .householdName(otherPrevious.getName())
                            .build()))
                .build());

    return ConnectAcceptanceFixture.builder()
        .profileId(orphan.getId())
        .homeHouseholdId(home.getId())
        .visitIds(List.of(firstVisit.getId(), secondVisit.getId()))
        .pendingOfferIds(List.of(firstPendingOffer.getId(), secondPendingOffer.getId()))
        .visitingSessionIds(List.of(firstWatching.getId(), secondWatching.getId()))
        .homeSessionId(homeWatching.getId())
        .reofferHouseholdIds(List.of(previous.getId(), otherPrevious.getId()))
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
  @DisplayName("Should reject a CONNECT acceptance when the Profile is already linked")
  void shouldRejectConnectAcceptanceWhenProfileAlreadyLinked() {
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
  }

  @Test
  @DisplayName("Should reject CONNECT acceptance when the Profile moves after invitation")
  void shouldRejectConnectAcceptanceWhenProfileMovesAfterInvitation() {
    var home = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var destination = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var orphan =
        profiles.save(ProfileFixture.defaultProfileBuilder().householdId(home.getId()).build());
    accounts.save(AccountFixture.defaultAccountBuilder().householdId(home.getId()).build());
    var issued =
        pendingConnectInvitation(
            ConnectInvitationFixture.builder()
                .profileId(orphan.getId())
                .householdId(home.getId())
                .build());
    orphan.setHouseholdId(destination.getId());

    var command = acceptCommand(issued.code());

    assertThatThrownBy(() -> service.accept(command))
        .isInstanceOf(InvalidOneTimeCodeException.class);
  }

  @Test
  @DisplayName(
      "Should reject CONNECT acceptance when a restricted Profile would become the first Account")
  void shouldRejectConnectAcceptanceWhenRestrictedProfileWouldBecomeFirstAccount() {
    var home = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var orphan =
        profiles.save(ProfileFixture.defaultProfileBuilder().householdId(home.getId()).build());
    var resident =
        accounts.save(AccountFixture.defaultAccountBuilder().householdId(home.getId()).build());
    var issued =
        pendingConnectInvitation(
            ConnectInvitationFixture.builder()
                .profileId(orphan.getId())
                .householdId(home.getId())
                .build());
    orphan.setKind(ProfileKind.KID);
    accounts.deleteById(resident.getId());

    var command = acceptCommand(issued.code());

    assertThatThrownBy(() -> service.accept(command))
        .isInstanceOf(InvalidOneTimeCodeException.class);
  }

  @Test
  @DisplayName("Should reject a lookup when the CONNECT Profile no longer exists")
  void shouldRejectLookupWhenConnectProfileNoLongerExists() {
    var home = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var vanished =
        pendingConnectInvitation(
            ConnectInvitationFixture.builder().householdId(home.getId()).build());
    var vanishedCode = vanished.code();
    assertThatThrownBy(() -> service.lookup(vanishedCode))
        .isInstanceOf(InvalidOneTimeCodeException.class);
  }

  @Test
  @DisplayName("Should reject an acceptance when the CONNECT Profile no longer exists")
  void shouldRejectAcceptanceWhenConnectProfileNoLongerExists() {
    var home = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var vanished =
        pendingConnectInvitation(
            ConnectInvitationFixture.builder().householdId(home.getId()).build());
    var vanishedCode = vanished.code();
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
                .reoffers(
                    List.of(
                        ReofferHouseholdFixture.builder()
                            .householdId(previous.getId())
                            .householdName(previous.getName())
                            .build()))
                .build());

    var preview = service.lookup(issued.code());

    assertThat(preview.mode()).isEqualTo(AccountInvitationMode.CONNECT);
    assertThat(preview.remainingManagers()).containsExactly("Nina");
    assertThat(preview.endingHouseholds()).containsExactly("Cabin");
    assertThat(preview.reofferHouseholds()).containsExactly("Cabin");
  }

  private OpaqueCodes.IssuedCode pendingConnectInvitation(ConnectInvitationFixture fixture) {
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
    if (fixture.reoffers() != null) {
      for (var reoffer : fixture.reoffers()) {
        reoffers.save(
            AccountInvitationReoffer.builder()
                .invitationId(invitation.getId())
                .householdId(reoffer.householdId())
                .householdName(reoffer.householdName())
                .build());
      }
    }

    return issued;
  }

  @Builder
  private record ConnectInvitationFixture(
      UUID profileId, UUID householdId, List<ReofferHouseholdFixture> reoffers) {}

  @Builder
  private record ReofferHouseholdFixture(UUID householdId, String householdName) {}

  @Builder
  private record ConnectAcceptanceFixture(
      UUID profileId,
      UUID homeHouseholdId,
      List<UUID> visitIds,
      List<UUID> pendingOfferIds,
      List<UUID> visitingSessionIds,
      UUID homeSessionId,
      List<UUID> reofferHouseholdIds,
      String code) {}

  private static AcceptInvitationCommand acceptCommand(String code) {
    return AcceptInvitationCommand.builder()
        .code(code)
        .displayName("Joe H")
        .password("a strong passphrase")
        .deviceName("web")
        .build();
  }

  private PendingInvitation.PendingInvitationBuilder pendingInvitationBuilder() {
    return PendingInvitation.builder().role(HouseholdRole.MEMBER).householdId(UUID.randomUUID());
  }

  private OpaqueCodes.IssuedCode pendingInvitation(PendingInvitation invitation) {
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

  private static final class PlainEncoder implements PasswordEncoder {
    @Override
    public String encode(CharSequence rawPassword) {
      return "hashed:" + rawPassword;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      return encodedPassword.equals(encode(rawPassword));
    }
  }
}
