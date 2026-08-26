package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.Argon2Properties;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.PasswordEncoderConfig;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AccountFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Login Service Tests")
class LoginServiceTest {

  private static final String CORRECT_PASSWORD = "correct horse battery staple";

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
  private final MutableClock clock = new MutableClock(currentTime);

  private final PasswordEncoder weakEncoder = encoderWith(4096, 1);
  private final PasswordEncoder serviceEncoder = encoderWith(8192, 2);
  private final CountingPasswordEncoder countingEncoder =
      new CountingPasswordEncoder(serviceEncoder);
  private final CountingTimingEqualizer timingEqualizer =
      new CountingTimingEqualizer(countingEncoder);

  private final FakeUserAccountRepository userAccountRepository = new FakeUserAccountRepository();
  private final FakeCredentialAttemptRepository credentialAttempts =
      new FakeCredentialAttemptRepository();

  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeRefreshTokenRepository tokenRepository = new FakeRefreshTokenRepository();
  private final RefreshTokenService refreshTokenService =
      new RefreshTokenService(
          sessionRepository,
          tokenRepository,
          AuthTokenProperties.builder()
              .signingKey("")
              .accessTokenTtl(Duration.ofMinutes(10))
              .refreshTokenTtl(Duration.ofDays(30))
              .rotationGrace(Duration.ofSeconds(30))
              .build(),
          clock,
          new TokenReuseRevoker(
              new TokenReuseRevocationWriter(sessionRepository, tokenRepository)));

  private final LoginService loginService =
      new LoginService(
          userAccountRepository,
          new LoginCompletionService(userAccountRepository, refreshTokenService),
          countingEncoder,
          credentialAttempts.gate(clock),
          timingEqualizer);

  @Test
  @DisplayName("Should refuse the login when the journal blocks the attempt")
  void shouldRefuseLoginWhenJournalBlocksAttempt() {
    var account = seedAccount(serviceEncoder.encode(CORRECT_PASSWORD));
    credentialAttempts.rejectReservations(Duration.ofMinutes(15));

    var correctAttempt = commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build();

    assertThatThrownBy(() -> loginService.login(correctAttempt))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName("Should not burn password verification cost when throttled")
  void shouldNotBurnPasswordVerificationCostWhenThrottled() {
    var account = seedAccount(serviceEncoder.encode(CORRECT_PASSWORD));
    credentialAttempts.rejectReservations(Duration.ofMinutes(15));
    var burnsBeforeThrottle = countingEncoder.completedVerifications();

    var throttledAttempt = commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build();
    assertThatThrownBy(() -> loginService.login(throttledAttempt))
        .isInstanceOf(TooManyLoginAttemptsException.class);

    assertThat(countingEncoder.completedVerifications()).isEqualTo(burnsBeforeThrottle);
  }

  @Test
  @DisplayName("Should reject credentials when stored hash unreadable")
  void shouldRejectCredentialsWhenStoredHashUnreadable() {
    // A real hash stripped of its {id} prefix — the corruption a migration bug would produce.
    var unreadableHash = serviceEncoder.encode(CORRECT_PASSWORD).replace("{argon2id}", "");
    var account = seedAccount(unreadableHash);

    var attempt = commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build();

    assertThatThrownBy(() -> loginService.login(attempt))
        .isInstanceOf(InvalidCredentialsException.class);
    // Exactly one equalizer burn — timing stays flat with every other rejection path.
    assertThat(countingEncoder.completedVerifications()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should reject login after one full-cost burn when stored hash empty")
  void shouldRejectLoginAfterOneFullCostBurnWhenStoredHashEmpty() {
    var account = seedAccount("");
    var attempt = commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build();

    assertThatThrownBy(() -> loginService.login(attempt))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(timingEqualizer.burns()).isEqualTo(1);
    assertThat(countingEncoder.completedVerifications()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should reject login after one full-cost burn when stored hash null")
  void shouldRejectLoginAfterOneFullCostBurnWhenStoredHashNull() {
    var account = seedAccount(null);
    var attempt = commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build();

    assertThatThrownBy(() -> loginService.login(attempt))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(timingEqualizer.burns()).isEqualTo(1);
    assertThat(countingEncoder.completedVerifications()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should rehash password when encoding upgrade needed")
  void shouldRehashPasswordWhenEncodingUpgradeNeeded() {
    var weakHash = weakEncoder.encode(CORRECT_PASSWORD);
    var account = seedAccount(weakHash);

    var result =
        loginService.login(commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build());

    assertThat(result.rawRefreshToken()).isNotBlank();
    assertThat(result.session().getAccountId()).isEqualTo(account.getId());

    var storedHash =
        userAccountRepository.findById(account.getId()).orElseThrow().getPasswordHash();
    assertThat(storedHash).isNotEqualTo(weakHash);
    assertThat(serviceEncoder.matches(CORRECT_PASSWORD, storedHash)).isTrue();
    assertThat(serviceEncoder.upgradeEncoding(storedHash)).isFalse();
  }

  @Test
  @DisplayName("Should reject login when email unknown")
  void shouldRejectLoginWhenEmailUnknown() {
    var attempt = commandBuilder("ghost@example.com").password(CORRECT_PASSWORD).build();

    assertThatThrownBy(() -> loginService.login(attempt))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(credentialAttempts.attempts())
        .singleElement()
        .satisfies(
            recorded -> {
              assertThat(recorded.target())
                  .isEqualTo(
                      CredentialAttemptTarget.builder()
                          .kind(CredentialKind.ACCOUNT_LOGIN)
                          .ipAddress("127.0.0.1")
                          .build());
              assertThat(recorded.result()).isEqualTo(CredentialAttemptResult.FAILED);
            });
  }

  @Test
  @DisplayName("Should reject login when account disabled")
  void shouldRejectLoginWhenAccountDisabled() {
    var account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(serviceEncoder.encode(CORRECT_PASSWORD))
                .enabled(false)
                .build());

    var attempt = commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build();

    assertThatThrownBy(() -> loginService.login(attempt))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  @DisplayName("Should burn password verification cost when email unknown")
  void shouldBurnPasswordVerificationCostWhenEmailUnknown() {
    var attempt = commandBuilder("ghost@example.com").password(CORRECT_PASSWORD).build();

    assertThatThrownBy(() -> loginService.login(attempt))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(countingEncoder.completedVerifications()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should burn password verification cost when account disabled")
  void shouldBurnPasswordVerificationCostWhenAccountDisabled() {
    var account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(serviceEncoder.encode(CORRECT_PASSWORD))
                .enabled(false)
                .build());

    var attempt = commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build();

    assertThatThrownBy(() -> loginService.login(attempt))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(countingEncoder.completedVerifications()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should keep stored hash when no encoding upgrade needed")
  void shouldKeepStoredHashWhenNoEncodingUpgradeNeeded() {
    var strongHash = serviceEncoder.encode(CORRECT_PASSWORD);
    var account = seedAccount(strongHash);

    loginService.login(commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build());

    assertThat(userAccountRepository.findById(account.getId()).orElseThrow().getPasswordHash())
        .isEqualTo(strongHash);
  }

  @Test
  @DisplayName(
      "Should journal each outcome in order when logins alternate between failure and success")
  void shouldJournalEachOutcomeInOrderWhenLoginsAlternateBetweenFailureAndSuccess() {
    var account = seedAccount(serviceEncoder.encode(CORRECT_PASSWORD));
    for (int i = 0; i < 2; i++) {
      var wrongAttempt = commandBuilder(account.getEmail()).password("wrong-" + i).build();
      assertThatThrownBy(() -> loginService.login(wrongAttempt))
          .isInstanceOf(InvalidCredentialsException.class);
    }
    loginService.login(commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build());
    var wrongAgain = commandBuilder(account.getEmail()).password("wrong-again").build();
    assertThatThrownBy(() -> loginService.login(wrongAgain))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(credentialAttempts.attempts())
        .allSatisfy(attempt -> assertThat(attempt.target().accountId()).isEqualTo(account.getId()))
        .extracting(FakeCredentialAttemptRepository.AttemptSnapshot::result)
        .containsExactly(
            CredentialAttemptResult.FAILED,
            CredentialAttemptResult.FAILED,
            CredentialAttemptResult.SUCCEEDED,
            CredentialAttemptResult.FAILED);
  }

  @Test
  @DisplayName(
      "Should reject login after one full-cost burn when the email is not shaped like an address")
  void shouldRejectLoginAfterOneFullCostBurnWhenEmailIsNotShapedLikeAnAddress() {
    var attempt = commandBuilder("ghost").password(CORRECT_PASSWORD).build();

    assertThatThrownBy(() -> loginService.login(attempt))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThat(timingEqualizer.burns()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should log in when the email has surrounding whitespace")
  void shouldLogInWhenEmailHasSurroundingWhitespace() {
    var account = seedAccount(serviceEncoder.encode(CORRECT_PASSWORD));
    var attempt =
        commandBuilder("  " + account.getEmail() + "\n").password(CORRECT_PASSWORD).build();

    var result = loginService.login(attempt);

    assertThat(result.account().getId()).isEqualTo(account.getId());
  }

  @Test
  @DisplayName("Should share the throttle budget when emails differ only by surrounding whitespace")
  void shouldShareThrottleBudgetWhenEmailsDifferOnlyBySurroundingWhitespace() {
    var account = seedAccount(serviceEncoder.encode(CORRECT_PASSWORD));

    for (int i = 0; i < 5; i++) {
      var padded = " ".repeat(i + 1) + account.getEmail();
      var wrongAttempt = commandBuilder(padded).password("wrong-" + i).build();
      assertThatThrownBy(() -> loginService.login(wrongAttempt))
          .isInstanceOf(InvalidCredentialsException.class);
    }

    var correctAttempt = commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build();

    assertThatThrownBy(() -> loginService.login(correctAttempt))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName("Should journal a failed attempt when login completion refuses the credential")
  void shouldJournalFailedAttemptWhenLoginCompletionRefusesCredential() {
    var account = seedAccount(serviceEncoder.encode(CORRECT_PASSWORD));
    var refusingCompletion =
        new LoginCompletionService(userAccountRepository, refreshTokenService) {
          @Override
          public LoginResult complete(LoginCompletionCommand command) {
            // The stored hash changed between verification and completion.
            throw new InvalidCredentialsException();
          }
        };
    var service =
        new LoginService(
            userAccountRepository,
            refusingCompletion,
            countingEncoder,
            credentialAttempts.gate(clock),
            timingEqualizer);
    var attempt = commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build();

    assertThatThrownBy(() -> service.login(attempt))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(credentialAttempts.attempts())
        .singleElement()
        .extracting(FakeCredentialAttemptRepository.AttemptSnapshot::result)
        .isEqualTo(CredentialAttemptResult.FAILED);
  }

  @Test
  @DisplayName("Should journal the same Account when the email casing differs")
  void shouldJournalSameAccountWhenEmailCasingDiffers() {
    var account = seedAccount(serviceEncoder.encode(CORRECT_PASSWORD));
    var upper =
        commandBuilder(account.getEmail().toUpperCase(Locale.ROOT)).password("wrong").build();
    var lower =
        commandBuilder(account.getEmail().toLowerCase(Locale.ROOT)).password("wrong").build();

    assertThatThrownBy(() -> loginService.login(upper))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThatThrownBy(() -> loginService.login(lower))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(credentialAttempts.attempts())
        .extracting(attempt -> attempt.target().accountId())
        .containsExactly(account.getId(), account.getId());
  }

  @Test
  @DisplayName("Should refuse the login when five failures fall within the window")
  void shouldRefuseLoginWhenFiveFailuresFallWithinTheWindow() {
    var account = seedAccount(serviceEncoder.encode(CORRECT_PASSWORD));
    for (int i = 0; i < 5; i++) {
      var wrongAttempt = commandBuilder(account.getEmail()).password("wrong-" + i).build();
      assertThatThrownBy(() -> loginService.login(wrongAttempt))
          .isInstanceOf(InvalidCredentialsException.class);
    }
    var correctAttempt = commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build();

    assertThatThrownBy(() -> loginService.login(correctAttempt))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName("Should forgive earlier failures when a login succeeds")
  void shouldForgiveEarlierFailuresWhenLoginSucceeds() {
    var account = seedAccount(serviceEncoder.encode(CORRECT_PASSWORD));
    for (int i = 0; i < 4; i++) {
      var wrongAttempt = commandBuilder(account.getEmail()).password("wrong-" + i).build();
      assertThatThrownBy(() -> loginService.login(wrongAttempt))
          .isInstanceOf(InvalidCredentialsException.class);
    }
    loginService.login(commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build());
    currentTime.updateAndGet(instant -> instant.plusSeconds(1));

    // Five fresh failures are admitted after the success; the sixth attempt is not.
    for (int i = 0; i < 5; i++) {
      var wrongAgain = commandBuilder(account.getEmail()).password("wrong-again-" + i).build();
      assertThatThrownBy(() -> loginService.login(wrongAgain))
          .isInstanceOf(InvalidCredentialsException.class);
    }
    var blocked = commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build();
    assertThatThrownBy(() -> loginService.login(blocked))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  private UserAccount seedAccount(String passwordHash) {
    return userAccountRepository.save(
        AccountFixture.defaultAccountBuilder().passwordHash(passwordHash).build());
  }

  private LoginCommand.LoginCommandBuilder commandBuilder(String email) {
    return LoginCommand.builder().email(email).deviceName("test-device").ipAddress("127.0.0.1");
  }

  private static PasswordEncoder encoderWith(int memoryKib, int iterations) {
    return new PasswordEncoderConfig()
        .passwordEncoder(
            Argon2Properties.builder()
                .memoryKib(memoryKib)
                .iterations(iterations)
                .parallelism(1)
                .build());
  }

  /**
   * Counts password verifications that run to completion. A verification that throws — an
   * unreadable stored hash — performs no hash work and must not count as a burn.
   */
  private static final class CountingPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final AtomicInteger completedVerifications = new AtomicInteger();

    private CountingPasswordEncoder(PasswordEncoder delegate) {
      this.delegate = delegate;
    }

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      var matched = delegate.matches(rawPassword, encodedPassword);
      completedVerifications.incrementAndGet();
      return matched;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
      return delegate.upgradeEncoding(encodedPassword);
    }

    private int completedVerifications() {
      return completedVerifications.get();
    }
  }

  private static final class CountingTimingEqualizer extends PasswordTimingEqualizer {

    private final AtomicInteger burns = new AtomicInteger();

    private CountingTimingEqualizer(PasswordEncoder passwordEncoder) {
      super(passwordEncoder);
    }

    @Override
    public void burn(String password) {
      burns.incrementAndGet();
      super.burn(password);
    }

    private int burns() {
      return burns.get();
    }
  }
}
