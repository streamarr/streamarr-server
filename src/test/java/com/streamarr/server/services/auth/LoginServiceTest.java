package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.Argon2Properties;
import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.PasswordEncoderConfig;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AccountFixture;
import java.time.Duration;
import java.time.Instant;
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

  private final FakeUserAccountRepository userAccountRepository = new FakeUserAccountRepository();

  private final LoginService loginService =
      new LoginService(
          userAccountRepository,
          new RefreshTokenService(
              new FakeAuthSessionRepository(),
              new FakeRefreshTokenRepository(),
              AuthTokenProperties.builder()
                  .signingKey("")
                  .accessTokenTtl(Duration.ofMinutes(10))
                  .refreshTokenTtl(Duration.ofDays(30))
                  .rotationGrace(Duration.ofSeconds(30))
                  .build(),
              clock,
              new CapturingEventPublisher()),
          serviceEncoder,
          new LoginThrottle(
              AuthThrottleProperties.builder()
                  .maxAttempts(5)
                  .window(Duration.ofMinutes(15))
                  .build(),
              clock));

  @Test
  @DisplayName("Should throttle when failures exceed limit")
  void shouldThrottleWhenFailuresExceedLimit() {
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
  @DisplayName("Should keep stored hash when no encoding upgrade needed")
  void shouldKeepStoredHashWhenNoEncodingUpgradeNeeded() {
    var strongHash = serviceEncoder.encode(CORRECT_PASSWORD);
    var account = seedAccount(strongHash);

    loginService.login(commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build());

    assertThat(userAccountRepository.findById(account.getId()).orElseThrow().getPasswordHash())
        .isEqualTo(strongHash);
  }

  @Test
  @DisplayName("Should restore throttle budget when login succeeds")
  void shouldRestoreThrottleBudgetWhenLoginSucceeds() {
    var account = seedAccount(serviceEncoder.encode(CORRECT_PASSWORD));

    for (int i = 0; i < 4; i++) {
      var wrongAttempt = commandBuilder(account.getEmail()).password("wrong-" + i).build();
      assertThatThrownBy(() -> loginService.login(wrongAttempt))
          .isInstanceOf(InvalidCredentialsException.class);
    }

    loginService.login(commandBuilder(account.getEmail()).password(CORRECT_PASSWORD).build());

    for (int i = 0; i < 5; i++) {
      var wrongAttempt =
          commandBuilder(account.getEmail())
              .password("wrong-again-" + i)
              .source("retry-src-" + i)
              .build();
      assertThatThrownBy(() -> loginService.login(wrongAttempt))
          .isInstanceOf(InvalidCredentialsException.class);
    }

    var blockedAttempt =
        commandBuilder(account.getEmail())
            .password(CORRECT_PASSWORD)
            .source("retry-src-final")
            .build();
    assertThatThrownBy(() -> loginService.login(blockedAttempt))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  @Test
  @DisplayName("Should keep source budget when other account succeeds from same source")
  void shouldKeepSourceBudgetWhenOtherAccountSucceedsFromSameSource() {
    var attacker = seedAccount(serviceEncoder.encode(CORRECT_PASSWORD));

    for (int i = 0; i < 4; i++) {
      var spray = commandBuilder("victim-" + i + "@example.com").password("guess").build();
      assertThatThrownBy(() -> loginService.login(spray))
          .isInstanceOf(InvalidCredentialsException.class);
    }

    loginService.login(commandBuilder(attacker.getEmail()).password(CORRECT_PASSWORD).build());

    var fifthSpray = commandBuilder("victim-4@example.com").password("guess").build();
    assertThatThrownBy(() -> loginService.login(fifthSpray))
        .isInstanceOf(InvalidCredentialsException.class);

    var sixthSpray = commandBuilder("victim-5@example.com").password("guess").build();
    assertThatThrownBy(() -> loginService.login(sixthSpray))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  private com.streamarr.server.domain.auth.UserAccount seedAccount(String passwordHash) {
    return userAccountRepository.save(
        AccountFixture.defaultAccountBuilder().passwordHash(passwordHash).build());
  }

  private LoginCommand.LoginCommandBuilder commandBuilder(String email) {
    return LoginCommand.builder().email(email).deviceName("test-device").source("127.0.0.1");
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
}
