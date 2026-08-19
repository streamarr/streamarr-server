package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AccountFixture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Account Password Verifier Tests")
class AccountPasswordVerifierTest {

  private static final String CORRECT_PASSWORD = "correct horse battery staple";
  private static final String UNREADABLE_HASH = "unreadable";

  private final RecordingPasswordEncoder encoder = new RecordingPasswordEncoder();
  private final CountingTimingEqualizer equalizer = new CountingTimingEqualizer(encoder);
  private final CredentialGuessThrottle throttle =
      new CredentialGuessThrottle(
          AuthThrottleProperties.builder().maxAttempts(2).window(Duration.ofMinutes(15)).build(),
          new MutableClock());
  private final AccountPasswordVerifier verifier =
      new AccountPasswordVerifier(encoder, equalizer, throttle);

  @Test
  @DisplayName("Should accept the correct password with one real comparison")
  void shouldAcceptCorrectPasswordWithOneRealComparison() {
    var account = enabledAccount(encoder.encode(CORRECT_PASSWORD));

    assertThatCode(() -> verifier.verify(account, CORRECT_PASSWORD)).doesNotThrowAnyException();

    assertThat(encoder.completedComparisons()).isEqualTo(1);
    assertThat(equalizer.burns()).isZero();
  }

  @Test
  @DisplayName("Should reject a wrong password with one real comparison")
  void shouldRejectWrongPasswordWithOneRealComparison() {
    var account = enabledAccount(encoder.encode(CORRECT_PASSWORD));

    assertThatThrownBy(() -> verifier.verify(account, "wrong"))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(encoder.completedComparisons()).isEqualTo(1);
    assertThat(equalizer.burns()).isZero();
  }

  @Test
  @DisplayName("Should reject a disabled Account after exactly one full-cost burn")
  void shouldRejectDisabledAccountAfterExactlyOneFullCostBurn() {
    var account =
        AccountFixture.defaultAccountBuilder()
            .id(UUID.randomUUID())
            .passwordHash(encoder.encode(CORRECT_PASSWORD))
            .enabled(false)
            .build();

    assertThatThrownBy(() -> verifier.verify(account, CORRECT_PASSWORD))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(equalizer.burns()).isEqualTo(1);
    assertThat(encoder.completedComparisons()).isEqualTo(1);
    assertThat(encoder.comparedAgainst()).doesNotContain(account.getPasswordHash());
  }

  @Test
  @DisplayName("Should reject an unreadable stored hash after exactly one full-cost burn")
  void shouldRejectUnreadableStoredHashAfterExactlyOneFullCostBurn() {
    var account = enabledAccount(UNREADABLE_HASH);

    assertThatThrownBy(() -> verifier.verify(account, CORRECT_PASSWORD))
        .isInstanceOf(InvalidCredentialsException.class);

    // The unreadable hash fails its parse cheaply; the burn is the one full-cost operation.
    assertThat(equalizer.burns()).isEqualTo(1);
    assertThat(encoder.completedComparisons()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should throttle before any Argon2 work when the budget is exhausted")
  void shouldThrottleBeforeAnyArgon2WorkWhenBudgetIsExhausted() {
    var account = enabledAccount(encoder.encode(CORRECT_PASSWORD));
    for (var attempt = 0; attempt < 2; attempt++) {
      assertThatThrownBy(() -> verifier.verify(account, "wrong"))
          .isInstanceOf(InvalidCredentialsException.class);
    }
    var comparisonsBeforeThrottle = encoder.completedComparisons();

    assertThatThrownBy(() -> verifier.verify(account, CORRECT_PASSWORD))
        .isInstanceOf(TooManyCredentialAttemptsException.class);

    assertThat(encoder.completedComparisons()).isEqualTo(comparisonsBeforeThrottle);
    assertThat(equalizer.burns()).isZero();
  }

  @Test
  @DisplayName("Should reset the budget after a successful verification")
  void shouldResetBudgetAfterSuccessfulVerification() {
    var account = enabledAccount(encoder.encode(CORRECT_PASSWORD));

    assertThatThrownBy(() -> verifier.verify(account, "wrong"))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThatCode(() -> verifier.verify(account, CORRECT_PASSWORD)).doesNotThrowAnyException();
    assertThatThrownBy(() -> verifier.verify(account, "wrong"))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThatCode(() -> verifier.verify(account, CORRECT_PASSWORD)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should share one Account password budget across callers")
  void shouldShareOneAccountPasswordBudgetAcrossCallers() {
    var account = enabledAccount(encoder.encode(CORRECT_PASSWORD));
    var otherCaller = new AccountPasswordVerifier(encoder, equalizer, throttle);

    assertThatThrownBy(() -> verifier.verify(account, "wrong"))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThatThrownBy(() -> otherCaller.verify(account, "wrong"))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThatThrownBy(() -> verifier.verify(account, CORRECT_PASSWORD))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should keep a password correct at request start sufficient")
  void shouldKeepPasswordCorrectAtRequestStartSufficient() {
    var originalHash = encoder.encode(CORRECT_PASSWORD);
    var account = enabledAccount(originalHash);
    // A concurrent rotation lands on the managed entity while the comparison is running.
    encoder.onNextComparison(() -> account.setPasswordHash(encoder.encode("rotated")));

    assertThatCode(() -> verifier.verify(account, CORRECT_PASSWORD)).doesNotThrowAnyException();

    assertThat(encoder.comparedAgainst()).containsExactly(originalHash);
  }

  private static UserAccount enabledAccount(String passwordHash) {
    return AccountFixture.defaultAccountBuilder()
        .id(UUID.randomUUID())
        .passwordHash(passwordHash)
        .build();
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

  /** Counts comparisons that run to completion; an unreadable hash fails before any hash work. */
  private static final class RecordingPasswordEncoder implements PasswordEncoder {

    private final AtomicInteger completedComparisons = new AtomicInteger();
    private final List<String> comparedAgainst = new ArrayList<>();
    private Runnable duringNextComparison = () -> {};

    @Override
    public String encode(CharSequence rawPassword) {
      return "encoded:" + rawPassword;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      if (UNREADABLE_HASH.equals(encodedPassword)) {
        throw new IllegalArgumentException("Unreadable test hash");
      }
      var hook = duringNextComparison;
      duringNextComparison = () -> {};
      hook.run();
      comparedAgainst.add(encodedPassword);
      completedComparisons.incrementAndGet();
      return encode(rawPassword).equals(encodedPassword);
    }

    private void onNextComparison(Runnable hook) {
      duringNextComparison = hook;
    }

    private int completedComparisons() {
      return completedComparisons.get();
    }

    private List<String> comparedAgainst() {
      return comparedAgainst;
    }
  }
}
