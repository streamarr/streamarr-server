package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.FakeAccountInvitationRepository;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.services.auth.AccountInvitationService.AcceptInvitationCommand;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
class AccountInvitationServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

  private final FakeAccountInvitationRepository invitations = new FakeAccountInvitationRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository();
  private final FakeProfileManagerRepository managers = new FakeProfileManagerRepository();
  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeRefreshTokenRepository refreshTokens = new FakeRefreshTokenRepository();
  private final OpaqueOneTimeCodes opaqueCodes = new OpaqueOneTimeCodes();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final CredentialGuessThrottle throttle =
      new CredentialGuessThrottle(
          AuthThrottleProperties.builder().maxAttempts(5).window(Duration.ofMinutes(15)).build(),
          clock);

  private final AccountInvitationService service =
      new AccountInvitationService(
          invitations,
          accounts,
          profiles,
          managers,
          shares,
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
  @DisplayName("Should preserve a valid invitation when opaque-code budget keys are exhausted")
  void shouldPreserveValidInvitationWhenOpaqueCodeBudgetKeysAreExhausted() {
    var issued = pendingInvitation(pendingInvitationBuilder().build());
    for (var key = 0; key < AuthThrottleProperties.DEFAULT_MAX_OPAQUE_CODE_BUDGETS; key++) {
      presentInvalidCode("sprayed-public-id-" + key + ".secret");
    }

    assertThatCode(() -> service.lookup(issued.code())).doesNotThrowAnyException();
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

  private void presentInvalidCode(String code) {
    assertThatThrownBy(() -> service.lookup(code)).isInstanceOf(InvalidOneTimeCodeException.class);
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
