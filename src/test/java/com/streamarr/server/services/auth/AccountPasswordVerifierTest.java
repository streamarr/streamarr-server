package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.Argon2Properties;
import com.streamarr.server.config.security.PasswordEncoderConfig;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Account Password Verifier Tests")
class AccountPasswordVerifierTest {

  private static final String CORRECT_PASSWORD = "correct horse battery staple";
  private static final String MALFORMED_ARGON2_HASH = "{argon2id}not-an-argon-hash";
  private static final String MALFORMED_BCRYPT_HASH = "{bcrypt}not-a-bcrypt-hash";
  private static final String UNREADABLE_HASH = "unreadable";
  private static final String IP_ADDRESS = "192.0.2.20";

  private final RecordingPasswordEncoder encoder = new RecordingPasswordEncoder();
  private final CountingTimingEqualizer equalizer = new CountingTimingEqualizer(encoder);
  private final MutableClock clock = new MutableClock();
  private final FakeCredentialAttemptRepository credentialAttempts =
      new FakeCredentialAttemptRepository();
  private final AccountPasswordVerifier verifier =
      new AccountPasswordVerifier(encoder, equalizer, credentialAttempts.gate(clock));

  @Test
  @DisplayName("Should accept password with one real comparison when password correct")
  void shouldAcceptPasswordWithOneRealComparisonWhenPasswordCorrect() {
    var account = enabledAccount(encoder.encode(CORRECT_PASSWORD));

    assertThatCode(() -> verifier.verify(account, CORRECT_PASSWORD, IP_ADDRESS))
        .doesNotThrowAnyException();

    assertThat(encoder.completedComparisons()).isEqualTo(1);
    assertThat(equalizer.burns()).isZero();
    assertThat(credentialAttempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.target())
                  .isEqualTo(
                      CredentialAttemptTarget.builder()
                          .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
                          .accountId(account.getId())
                          .ipAddress(IP_ADDRESS)
                          .build());
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.SUCCEEDED);
            });
  }

  @Test
  @DisplayName("Should reject password with one real comparison when password wrong")
  void shouldRejectPasswordWithOneRealComparisonWhenPasswordWrong() {
    var account = enabledAccount(encoder.encode(CORRECT_PASSWORD));

    assertThatThrownBy(() -> verifier.verify(account, "wrong", IP_ADDRESS))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(encoder.completedComparisons()).isEqualTo(1);
    assertThat(equalizer.burns()).isZero();
  }

  @Test
  @DisplayName("Should reject password after one full-cost burn when Account disabled")
  void shouldRejectPasswordAfterOneFullCostBurnWhenAccountDisabled() {
    var account =
        AccountFixture.defaultAccountBuilder()
            .id(UUID.randomUUID())
            .passwordHash(encoder.encode(CORRECT_PASSWORD))
            .enabled(false)
            .build();

    assertThatThrownBy(() -> verifier.verify(account, CORRECT_PASSWORD, IP_ADDRESS))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(equalizer.burns()).isEqualTo(1);
    assertThat(encoder.completedComparisons()).isEqualTo(1);
    assertThat(encoder.comparedAgainst()).doesNotContain(account.getPasswordHash());
  }

  @Test
  @DisplayName("Should reject password after one full-cost burn when stored hash unreadable")
  void shouldRejectPasswordAfterOneFullCostBurnWhenStoredHashUnreadable() {
    var account = enabledAccount(UNREADABLE_HASH);

    assertThatThrownBy(() -> verifier.verify(account, CORRECT_PASSWORD, IP_ADDRESS))
        .isInstanceOf(InvalidCredentialsException.class);

    // The unreadable hash fails its parse cheaply; the burn is the one full-cost operation.
    assertThat(equalizer.burns()).isEqualTo(1);
    assertThat(encoder.completedComparisons()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should reject password after one full-cost burn when Argon2 hash malformed")
  void shouldRejectPasswordAfterOneFullCostBurnWhenArgon2HashMalformed() {
    var productionEncoder = productionPasswordEncoder();
    var productionEqualizer = new CountingTimingEqualizer(productionEncoder);
    var productionVerifier =
        new AccountPasswordVerifier(
            productionEncoder, productionEqualizer, credentialAttempts.gate(clock));
    var account = enabledAccount(MALFORMED_ARGON2_HASH);

    assertThatThrownBy(() -> productionVerifier.verify(account, CORRECT_PASSWORD, IP_ADDRESS))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(productionEqualizer.burns()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should reject password after one full-cost burn when bcrypt hash malformed")
  void shouldRejectPasswordAfterOneFullCostBurnWhenBcryptHashMalformed() {
    var productionEncoder = productionPasswordEncoder();
    var productionEqualizer = new CountingTimingEqualizer(productionEncoder);
    var productionVerifier =
        new AccountPasswordVerifier(
            productionEncoder, productionEqualizer, credentialAttempts.gate(clock));
    var account = enabledAccount(MALFORMED_BCRYPT_HASH);

    assertThatThrownBy(() -> productionVerifier.verify(account, CORRECT_PASSWORD, IP_ADDRESS))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(productionEqualizer.burns()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{argon2id}", "{bcrypt}"})
  @DisplayName("Should reject password after one full-cost burn when recognized hash payload empty")
  void shouldRejectPasswordAfterOneFullCostBurnWhenRecognizedHashPayloadEmpty(String passwordHash) {
    var productionEncoder = new DelegatingRecordingPasswordEncoder(productionPasswordEncoder());
    var productionEqualizer = new CountingTimingEqualizer(productionEncoder);
    var productionVerifier =
        new AccountPasswordVerifier(
            productionEncoder, productionEqualizer, credentialAttempts.gate(clock));
    var account = enabledAccount(passwordHash);

    assertThatThrownBy(() -> productionVerifier.verify(account, CORRECT_PASSWORD, IP_ADDRESS))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(productionEqualizer.burns()).isEqualTo(1);
    assertThat(productionEncoder.comparedAgainst()).hasSize(1).doesNotContain(passwordHash);
  }

  @Test
  @DisplayName("Should reject password after one full-cost burn when stored hash empty")
  void shouldRejectPasswordAfterOneFullCostBurnWhenStoredHashEmpty() {
    var account = enabledAccount("");

    assertThatThrownBy(() -> verifier.verify(account, CORRECT_PASSWORD, IP_ADDRESS))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(equalizer.burns()).isEqualTo(1);
    assertThat(encoder.completedComparisons()).isEqualTo(1);
    assertThat(encoder.comparedAgainst()).doesNotContain("");
  }

  @Test
  @DisplayName("Should reject password after one full-cost burn when stored hash null")
  void shouldRejectPasswordAfterOneFullCostBurnWhenStoredHashNull() {
    var account = enabledAccount(null);

    assertThatThrownBy(() -> verifier.verify(account, CORRECT_PASSWORD, IP_ADDRESS))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(equalizer.burns()).isEqualTo(1);
    assertThat(encoder.completedComparisons()).isEqualTo(1);
    assertThat(encoder.comparedAgainst()).doesNotContainNull();
  }

  @Test
  @DisplayName("Should refuse before any Argon2 work when the journal blocks the attempt")
  void shouldRefuseBeforeAnyArgon2WorkWhenJournalBlocksAttempt() {
    var account = enabledAccount(encoder.encode(CORRECT_PASSWORD));
    var comparisonsBeforeThrottle = encoder.completedComparisons();
    credentialAttempts.rejectReservations(Duration.ofMinutes(15));

    assertThatThrownBy(() -> verifier.verify(account, CORRECT_PASSWORD, IP_ADDRESS))
        .isInstanceOf(TooManyCredentialAttemptsException.class);

    assertThat(encoder.completedComparisons()).isEqualTo(comparisonsBeforeThrottle);
    assertThat(equalizer.burns()).isZero();
  }

  @Test
  @DisplayName("Should limit only the failing Account when another Account verifies")
  void shouldLimitOnlyTheFailingAccountWhenAnotherAccountVerifies() {
    var account = enabledAccount(encoder.encode(CORRECT_PASSWORD));
    var otherAccount = enabledAccount(encoder.encode(CORRECT_PASSWORD));
    for (var attempt = 0; attempt < 5; attempt++) {
      assertThatThrownBy(() -> verifier.verify(account, "wrong", IP_ADDRESS))
          .isInstanceOf(InvalidCredentialsException.class);
    }

    assertThatThrownBy(() -> verifier.verify(account, CORRECT_PASSWORD, IP_ADDRESS))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
    assertThatCode(() -> verifier.verify(otherAccount, CORRECT_PASSWORD, IP_ADDRESS))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should journal each outcome in order when verifications alternate")
  void shouldJournalEachOutcomeInOrderWhenVerificationsAlternate() {
    var account = enabledAccount(encoder.encode(CORRECT_PASSWORD));
    assertThatThrownBy(() -> verifier.verify(account, "wrong", IP_ADDRESS))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThatCode(() -> verifier.verify(account, CORRECT_PASSWORD, IP_ADDRESS))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> verifier.verify(account, "wrong", IP_ADDRESS))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(credentialAttempts.attempts())
        .extracting(FakeCredentialAttemptRepository.AttemptSnapshot::result)
        .containsExactly(
            CredentialAttemptResult.FAILED,
            CredentialAttemptResult.SUCCEEDED,
            CredentialAttemptResult.FAILED);
  }

  private static UserAccount enabledAccount(String passwordHash) {
    return AccountFixture.defaultAccountBuilder()
        .id(UUID.randomUUID())
        .passwordHash(passwordHash)
        .build();
  }

  private static PasswordEncoder productionPasswordEncoder() {
    return new PasswordEncoderConfig()
        .passwordEncoder(
            Argon2Properties.builder().memoryKib(4096).iterations(1).parallelism(1).build());
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

  private static final class DelegatingRecordingPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final List<String> comparedAgainst = new ArrayList<>();

    private DelegatingRecordingPasswordEncoder(PasswordEncoder delegate) {
      this.delegate = delegate;
    }

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      comparedAgainst.add(encodedPassword);
      return delegate.matches(rawPassword, encodedPassword);
    }

    private List<String> comparedAgainst() {
      return comparedAgainst;
    }
  }
}
