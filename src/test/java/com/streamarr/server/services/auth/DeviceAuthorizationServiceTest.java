package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.CanonicalBaseUrl;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.DeviceAuthProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.DeviceCodeExpiredException;
import com.streamarr.server.exceptions.DeviceCodeNotFoundException;
import com.streamarr.server.exceptions.DeviceCodeNotPendingException;
import com.streamarr.server.exceptions.DevicePairingNotConfiguredException;
import com.streamarr.server.exceptions.InvalidUserCodeException;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import com.streamarr.server.fakes.FakeAccountProfileRepository;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeDeviceAuthorizationRepository;
import com.streamarr.server.fakes.FakeHouseholdMembershipRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.DeviceAuthorizationDecisionCommand;
import com.streamarr.server.repositories.auth.DeviceAuthorizationInsertCommand;
import com.streamarr.server.repositories.auth.DeviceAuthorizationInsertResult;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("UnitTest")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Device Authorization Service Tests")
class DeviceAuthorizationServiceTest {

  private static final String BASE_URL = "https://home.example.com";

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

  private final MutableClock clock = new MutableClock(currentTime);

  private final FakeDeviceAuthorizationRepository authorizationRepository =
      new FakeDeviceAuthorizationRepository();
  private final FakeUserAccountRepository userAccountRepository = new FakeUserAccountRepository();
  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeRefreshTokenRepository tokenRepository = new FakeRefreshTokenRepository();
  private final FakeHouseholdMembershipRepository membershipRepository =
      new FakeHouseholdMembershipRepository();
  private final FakeAccountProfileRepository accountProfileRepository =
      new FakeAccountProfileRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();

  private final DeviceAuthProperties properties =
      DeviceAuthProperties.builder()
          .codeTtl(Duration.ofMinutes(10))
          .pollIntervalSeconds(5)
          .verificationPath("/link")
          .maxOutstandingCodes(3)
          .maxGuessAttempts(5)
          .guessWindow(Duration.ofMinutes(15))
          .sweepInterval(Duration.ofMinutes(15))
          .build();

  private static final String TEST_KEY_BASE64 =
      "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQga+ZKCbAcyZIb7k2FE8rMPFtIpTdzX2dR/csZ8k6A95uhRANCAAQawOmVKMDLAOsboxKLb9khGsWyxwcIikucXDCfX18ME5X9/kqSS2vdMnFfZ6KR12U/Sy/EwOwnc82xFAyFdNbe";

  private final AuthTokenProperties tokenProperties =
      AuthTokenProperties.builder()
          .signingKey(TEST_KEY_BASE64)
          .accessTokenTtl(Duration.ofMinutes(10))
          .refreshTokenTtl(Duration.ofDays(30))
          .rotationGrace(Duration.ofSeconds(30))
          .build();

  private final TokenCryptoConfig cryptoConfig = new TokenCryptoConfig();

  private final RefreshTokenService refreshTokenService =
      new RefreshTokenService(
          sessionRepository,
          tokenRepository,
          tokenProperties,
          clock,
          new TokenReuseRevoker(
              new TokenReuseRevocationWriter(sessionRepository, tokenRepository)));

  private final SessionScopeService sessionScopeService =
      new SessionScopeService(
          membershipRepository,
          accountProfileRepository,
          sessionRepository,
          userAccountRepository,
          clock);

  private final AccessTokenIssuer accessTokenIssuer =
      new AccessTokenIssuer(
          cryptoConfig.jwtEncoder(cryptoConfig.tokenSigningKeys(tokenProperties)),
          tokenProperties,
          clock,
          membershipRepository,
          profileRepository,
          accountProfileRepository);

  private DeviceAuthorizationService service;
  private UserAccount approver;

  @BeforeEach
  void createService() {
    service = serviceWith(CanonicalBaseUrl.of(BASE_URL, false));
    approver = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
  }

  @Test
  @DisplayName("Should return an absolute verification URI and the server's own timings")
  void shouldReturnAbsoluteVerificationUriAndServersOwnTimings() {
    var issued = service.issue("Apple TV");

    assertThat(issued.verificationUri()).isEqualTo(BASE_URL + "/link");
    assertThat(issued.interval()).isEqualTo(5);
    assertThat(issued.expiresIn()).isEqualTo(600);
    assertThat(issued.userCode()).matches("[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}");
    assertThat(issued.deviceCode()).hasSize(43);
  }

  @Test
  @DisplayName("Should never store the raw device code")
  void shouldNeverStoreRawDeviceCode() {
    var issued = service.issue("Apple TV");

    assertThat(authorizationRepository.findAll())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getDeviceCodeDigest()).isNotEqualTo(issued.deviceCode());
              assertThat(row.getStatus()).isEqualTo(DeviceAuthorizationStatus.PENDING);
              assertThat(row.getDeviceName()).isEqualTo("Apple TV");
            });
  }

  @Test
  @DisplayName("Should refuse issuance when no canonical base URL is configured")
  void shouldRefuseIssuanceWhenNoCanonicalBaseUrlConfigured() {
    var unconfigured = serviceWith(CanonicalBaseUrl.absent());

    assertThat(unconfigured.isPairingEnabled()).isFalse();
    assertThatThrownBy(() -> unconfigured.issue("Apple TV"))
        .isInstanceOf(DevicePairingNotConfiguredException.class);
  }

  @Test
  @DisplayName("Should let an already-issued grant finish after issuance is disabled")
  void shouldLetAlreadyIssuedGrantFinishAfterIssuanceDisabled() {
    var issued = service.issue("Apple TV");

    // Only new issuance is gated; a code already shown to a person must never be stranded.
    var unconfigured = serviceWith(CanonicalBaseUrl.absent());
    assertThat(unconfigured.lookup(issued.userCode(), approver.getId()).status())
        .isEqualTo(DeviceAuthorizationStatus.PENDING);
    unconfigured.decide(decisionCommand(issued.userCode()));

    assertThat(unconfigured.redeem(issued.deviceCode()))
        .isInstanceOf(DevicePollResult.Success.class);
  }

  @Test
  @DisplayName("Should report pending until the code is approved")
  void shouldReportPendingUntilCodeIsApproved() {
    var issued = service.issue("Apple TV");

    advanceClock(Duration.ofSeconds(5));

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Pending.class);
  }

  /**
   * RFC 8628 §3.2 defines the interval as the wait <em>between</em> polling requests. Nothing
   * precedes the first one, so a device that polls the moment it holds a code is conforming, and
   * charging it a permanent cumulative penalty would halve how often it notices its own approval.
   */
  @Test
  @DisplayName("Should allow the first poll immediately and leave the interval untouched")
  void shouldAllowFirstPollImmediatelyAndLeaveIntervalUntouched() {
    var issued = service.issue("Apple TV");

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Pending.class);
    assertThat(storedInterval()).isEqualTo(5);
  }

  @Test
  @DisplayName("Should slow down a caller that polls before the cadence allows")
  void shouldSlowDownCallerThatPollsBeforeCadenceAllows() {
    var issued = service.issue("Apple TV");
    service.redeem(issued.deviceCode());

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.SlowDown.class);
  }

  @Test
  @DisplayName("Should raise the interval five seconds per early poll, cumulatively")
  void shouldRaiseIntervalFiveSecondsPerEarlyPollCumulatively() {
    var issued = service.issue("Apple TV");

    // Issued at t=0, and the gate opens on the poll before, so the first one is never early.
    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Pending.class);
    assertThat(storedInterval()).isEqualTo(5);

    advanceClock(Duration.ofSeconds(4));
    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.SlowDown.class);
    assertThat(storedInterval()).isEqualTo(10);

    advanceClock(Duration.ofSeconds(5));
    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.SlowDown.class);
    assertThat(storedInterval()).isEqualTo(15);

    advanceClock(Duration.ofSeconds(15));
    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Pending.class);
    assertThat(storedInterval()).isEqualTo(15);
  }

  @Test
  @DisplayName("Should mint a session at the winning poll rather than at approval")
  void shouldMintSessionAtWinningPollRatherThanAtApproval() {
    var issued = service.issue("Apple TV");
    approve(issued.userCode());

    assertThat(sessionRepository.findAll()).isEmpty();

    var result = service.redeem(issued.deviceCode());

    assertThat(result).isInstanceOf(DevicePollResult.Success.class);
    assertThat(sessionRepository.findAll()).singleElement();
    assertThat(sessionRepository.findAll().getFirst().getDeviceName()).isEqualTo("Apple TV");
    assertThat(storedStatus()).isEqualTo(DeviceAuthorizationStatus.CONSUMED);
  }

  @Test
  @DisplayName("Should report expired once the grant has been consumed")
  void shouldReportExpiredOnceGrantHasBeenConsumed() {
    var issued = service.issue("Apple TV");
    approve(issued.userCode());
    service.redeem(issued.deviceCode());

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Expired.class);
    assertThat(sessionRepository.findAll()).hasSize(1);
  }

  @Test
  @DisplayName("Should report denial as its own terminal state")
  void shouldReportDenialAsItsOwnTerminalState() {
    var issued = service.issue("Apple TV");
    decide(issued.userCode(), DeviceDecision.DENY);

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Denied.class);
    assertThat(sessionRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should report expired once the code's lifetime has passed")
  void shouldReportExpiredOnceCodesLifetimeHasPassed() {
    var issued = service.issue("Apple TV");
    approve(issued.userCode());

    advanceClock(Duration.ofMinutes(11));

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Expired.class);
    assertThat(sessionRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should report expired for a malformed device code without touching the database")
  void shouldReportExpiredForMalformedDeviceCodeWithoutTouchingDatabase() {
    assertThat(service.redeem("not-a-device-code")).isInstanceOf(DevicePollResult.Expired.class);
    assertThat(service.redeem(null)).isInstanceOf(DevicePollResult.Expired.class);
  }

  @Test
  @DisplayName("Should show the requesting device before an approver commits")
  void shouldShowRequestingDeviceBeforeApproverCommits() {
    var issued = service.issue("Living Room Apple TV");

    var view = service.lookup(issued.userCode(), approver.getId());

    assertThat(view.deviceName()).isEqualTo("Living Room Apple TV");
    assertThat(view.status()).isEqualTo(DeviceAuthorizationStatus.PENDING);
    assertThat(view.userCode()).isEqualTo(issued.userCode());
    assertThat(view.requestedAt()).isNotNull();
  }

  @Test
  @DisplayName("Should accept a typed code in any case with or without the separator")
  void shouldAcceptTypedCodeInAnyCaseWithOrWithoutSeparator() {
    var issued = service.issue("Apple TV");

    assertThat(service.lookup(issued.userCode().toLowerCase(Locale.ROOT), approver.getId()))
        .isNotNull();
    assertThat(service.lookup(issued.userCode().replace("-", ""), approver.getId())).isNotNull();
  }

  @Test
  @DisplayName("Should collapse an expired code into not-found on lookup")
  void shouldCollapseExpiredCodeIntoNotFoundOnLookup() {
    var issued = service.issue("Apple TV");

    advanceClock(Duration.ofMinutes(11));

    var userCode = issued.userCode();
    var approverId = approver.getId();

    // A probe deserves no oracle detail; the poll answers expired_token for the same state.
    assertThatThrownBy(() -> service.lookup(userCode, approverId))
        .isInstanceOf(DeviceCodeNotFoundException.class);
  }

  @Test
  @DisplayName("Should tell an approver that a code expired rather than that it is unknown")
  void shouldTellApproverThatCodeExpiredRatherThanThatItIsUnknown() {
    var issued = service.issue("Apple TV");

    advanceClock(Duration.ofMinutes(11));

    var userCode = issued.userCode();

    assertThatThrownBy(() -> approve(userCode)).isInstanceOf(DeviceCodeExpiredException.class);
  }

  @Test
  @DisplayName("Should reject a second decision on an already decided request")
  void shouldRejectSecondDecisionOnAlreadyDecidedRequest() {
    var issued = service.issue("Apple TV");
    approve(issued.userCode());

    var userCode = issued.userCode();

    assertThatThrownBy(() -> decide(userCode, DeviceDecision.DENY))
        .isInstanceOf(DeviceCodeNotPendingException.class);
  }

  @Test
  @DisplayName("Should reject a malformed user code before looking anything up")
  void shouldRejectMalformedUserCodeBeforeLookingAnythingUp() {
    var approverId = approver.getId();

    assertThatThrownBy(() -> service.lookup("NOPE", approverId))
        .isInstanceOf(InvalidUserCodeException.class);
  }

  @Test
  @DisplayName("Should echo the decision that actually happened")
  void shouldEchoDecisionThatActuallyHappened() {
    var issued = service.issue("Apple TV");

    var view = decide(issued.userCode(), DeviceDecision.DENY);

    assertThat(view.status()).isEqualTo(DeviceAuthorizationStatus.DENIED);
    assertThat(view.deviceName()).isEqualTo("Apple TV");
    assertThat(storedStatus()).isEqualTo(DeviceAuthorizationStatus.DENIED);
  }

  @Test
  @DisplayName("Should record who decided, for a denial as much as an approval")
  void shouldRecordWhoDecidedForDenialAsMuchAsApproval() {
    var issued = service.issue("Apple TV");
    decide(issued.userCode(), DeviceDecision.DENY);

    assertThat(authorizationRepository.findAll())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getDecidedByAccountId()).isEqualTo(approver.getId());
              assertThat(row.getDecidedAt()).isNotNull();
            });
  }

  @Test
  @DisplayName("Should refuse issuance once the outstanding-code cap is reached")
  void shouldRefuseIssuanceOnceOutstandingCodeCapReached() {
    service.issue("One");
    service.issue("Two");
    service.issue("Three");

    assertThatThrownBy(() -> service.issue("Four"))
        .isInstanceOf(TooManyDeviceAttemptsException.class)
        .satisfies(
            e ->
                assertThat(((TooManyDeviceAttemptsException) e).getRetryAfter())
                    // The moment capacity provably frees: when the oldest code expires.
                    .isEqualTo(Duration.ofMinutes(10)));
  }

  @Test
  @DisplayName("Should free issuance capacity once outstanding codes expire")
  void shouldFreeIssuanceCapacityOnceOutstandingCodesExpire() {
    service.issue("One");
    service.issue("Two");
    service.issue("Three");

    advanceClock(Duration.ofMinutes(11));

    assertThat(service.issue("Four").deviceCode()).isNotBlank();
  }

  @ParameterizedTest
  @EnumSource(DeviceDecision.class)
  @DisplayName("Should free issuance capacity when an outstanding code is decided")
  void shouldFreeIssuanceCapacityWhenOutstandingCodeDecided(DeviceDecision decision) {
    var first = service.issue("One");
    service.issue("Two");
    service.issue("Three");

    decide(first.userCode(), decision);

    assertThat(service.issue("Four").deviceCode()).isNotBlank();
  }

  @Test
  @DisplayName("Should warn only when issuance first nears and then reaches capacity")
  void shouldWarnOnlyWhenIssuanceFirstNearsAndThenReachesCapacity(CapturedOutput output) {
    var capacityService = serviceWithCapacity(4);

    capacityService.issue("One");
    capacityService.issue("Two");
    capacityService.issue("Three");
    capacityService.issue("Four");

    assertThat(output.getAll())
        .contains("issuance at 2 of 4")
        .doesNotContain("issuance at 3 of 4")
        .contains("issuance at 4 of 4");
  }

  @Test
  @DisplayName("Should use the atomic outstanding count returned by insertion")
  void shouldUseAtomicOutstandingCountReturnedByInsertion() {
    var atomicRepository =
        new FakeDeviceAuthorizationRepository() {
          private boolean inserted;

          @Override
          public DeviceAuthorizationInsertResult tryInsertWithinCap(
              DeviceAuthorizationInsertCommand command) {
            var result = super.tryInsertWithinCap(command);
            inserted = result.inserted();
            return result;
          }

          @Override
          public int countOutstanding(Instant now) {
            if (inserted) {
              throw new IllegalStateException("Post-insert recount escaped the atomic operation.");
            }
            return super.countOutstanding(now);
          }
        };
    var atomicService = serviceWith(atomicRepository, new UserCodeGenerator());

    assertThatCode(() -> atomicService.issue("Apple TV")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should rethrow an integrity failure unrelated to the user-code constraint")
  void shouldRethrowIntegrityFailureUnrelatedToUserCodeConstraint() {
    var failure = constraintViolation("uq_device_authorization_device_code_digest");
    var throwingRepository =
        new FakeDeviceAuthorizationRepository() {
          @Override
          public DeviceAuthorizationInsertResult tryInsertWithinCap(
              DeviceAuthorizationInsertCommand command) {
            throw failure;
          }
        };
    var failingService = serviceWith(throwingRepository, new UserCodeGenerator());

    assertThatThrownBy(() -> failingService.issue("Apple TV")).isSameAs(failure);
  }

  @Test
  @DisplayName("Should retry a collision on the user-code constraint")
  void shouldRetryCollisionOnUserCodeConstraint() {
    var candidates = new ArrayDeque<>(List.of("BBBBBBBB", "BBBBBBBB", "CCCCCCCC"));
    var collidingService =
        serviceWith(
            authorizationRepository,
            new UserCodeGenerator() {
              @Override
              public String generate() {
                return candidates.removeFirst();
              }
            });

    assertThat(collidingService.issue("First").userCode()).isEqualTo("BBBB-BBBB");
    assertThat(collidingService.issue("Second").userCode()).isEqualTo("CCCC-CCCC");
    assertThat(authorizationRepository.findAll()).hasSize(2);
  }

  @Test
  @DisplayName("Should retain the final user-code collision when retries are exhausted")
  void shouldRetainFinalUserCodeCollisionWhenRetriesExhausted() {
    var collidingService =
        serviceWith(
            authorizationRepository,
            new UserCodeGenerator() {
              @Override
              public String generate() {
                return "BBBBBBBB";
              }
            });
    collidingService.issue("First");

    assertThatThrownBy(() -> collidingService.issue("Second"))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Should fail fast when a lost decision still observes a live pending row")
  void shouldFailFastWhenLostDecisionStillObservesLivePendingRow() {
    var losingRepository =
        new FakeDeviceAuthorizationRepository() {
          @Override
          public int decide(DeviceAuthorizationDecisionCommand command) {
            return 0;
          }
        };
    var losingService = serviceWith(losingRepository, new UserCodeGenerator());
    var issued = losingService.issue("Apple TV");
    var command =
        DeviceDecisionCommand.builder()
            .userCode(issued.userCode())
            .decision(DeviceDecision.APPROVE)
            .decidedByAccountId(approver.getId())
            .build();

    assertThatThrownBy(() -> losingService.decide(command))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pending");
  }

  @Test
  @DisplayName("Should report not pending when another decision wins the conditional update")
  void shouldReportNotPendingWhenAnotherDecisionWinsConditionalUpdate() {
    var losingRepository =
        new FakeDeviceAuthorizationRepository() {
          @Override
          public int decide(DeviceAuthorizationDecisionCommand command) {
            super.decide(command);
            return 0;
          }
        };
    var losingService = serviceWith(losingRepository, new UserCodeGenerator());
    var issued = losingService.issue("Apple TV");
    var command = decisionCommand(issued.userCode());

    assertThatThrownBy(() -> losingService.decide(command))
        .isInstanceOf(DeviceCodeNotPendingException.class);
  }

  @Test
  @DisplayName("Should report not found when the row vanishes before the conditional update")
  void shouldReportNotFoundWhenRowVanishesBeforeConditionalUpdate() {
    var losingRepository =
        new FakeDeviceAuthorizationRepository() {
          @Override
          public int decide(DeviceAuthorizationDecisionCommand command) {
            findByUserCode(command.userCode()).ifPresent(row -> deleteById(row.getId()));
            return 0;
          }
        };
    var losingService = serviceWith(losingRepository, new UserCodeGenerator());
    var issued = losingService.issue("Apple TV");
    var command = decisionCommand(issued.userCode());

    assertThatThrownBy(() -> losingService.decide(command))
        .isInstanceOf(DeviceCodeNotFoundException.class);
  }

  @Test
  @DisplayName("Should refuse a grant whose approver has since been disabled")
  void shouldRefuseGrantWhoseApproverHasSinceBeenDisabled() {
    var issued = service.issue("Apple TV");
    approve(issued.userCode());

    approver.setEnabled(false);
    userAccountRepository.save(approver);

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Expired.class);
    assertThat(sessionRepository.findAll()).isEmpty();
  }

  private DeviceAuthorizationService serviceWith(CanonicalBaseUrl baseUrl) {
    return serviceWith(authorizationRepository, new UserCodeGenerator(), baseUrl);
  }

  private DeviceAuthorizationService serviceWith(
      DeviceAuthorizationRepository repository, UserCodeGenerator generator) {
    return serviceWith(repository, generator, CanonicalBaseUrl.of(BASE_URL, false));
  }

  private DeviceAuthorizationService serviceWith(
      DeviceAuthorizationRepository repository,
      UserCodeGenerator generator,
      CanonicalBaseUrl baseUrl) {
    return new DeviceAuthorizationService(
        repository,
        userAccountRepository,
        refreshTokenService,
        sessionScopeService,
        accessTokenIssuer,
        generator,
        new DeviceGuessThrottle(properties, clock),
        properties,
        baseUrl,
        clock);
  }

  private DeviceAuthorizationService serviceWithCapacity(int capacity) {
    var capacityProperties =
        DeviceAuthProperties.builder()
            .codeTtl(properties.codeTtl())
            .pollIntervalSeconds(properties.pollIntervalSeconds())
            .verificationPath(properties.verificationPath())
            .maxOutstandingCodes(capacity)
            .maxGuessAttempts(properties.maxGuessAttempts())
            .guessWindow(properties.guessWindow())
            .sweepInterval(properties.sweepInterval())
            .build();
    return new DeviceAuthorizationService(
        authorizationRepository,
        userAccountRepository,
        refreshTokenService,
        sessionScopeService,
        accessTokenIssuer,
        new UserCodeGenerator(),
        new DeviceGuessThrottle(capacityProperties, clock),
        capacityProperties,
        CanonicalBaseUrl.of(BASE_URL, false),
        clock);
  }

  private static DataIntegrityViolationException constraintViolation(String constraintName) {
    var message = "duplicate key value violates unique constraint \"%s\"".formatted(constraintName);
    return new DataIntegrityViolationException(
        message,
        new ConstraintViolationException(
            message, new SQLException(message, "23505"), constraintName));
  }

  private DeviceAuthorizationView approve(String userCode) {
    return decide(userCode, DeviceDecision.APPROVE);
  }

  private DeviceDecisionCommand decisionCommand(String userCode) {
    return DeviceDecisionCommand.builder()
        .userCode(userCode)
        .decision(DeviceDecision.APPROVE)
        .decidedByAccountId(approver.getId())
        .build();
  }

  private DeviceAuthorizationView decide(String userCode, DeviceDecision decision) {
    return service.decide(
        DeviceDecisionCommand.builder()
            .userCode(userCode)
            .decision(decision)
            .decidedByAccountId(approver.getId())
            .build());
  }

  private int storedInterval() {
    return authorizationRepository.findAll().getFirst().getPollIntervalSeconds();
  }

  private DeviceAuthorizationStatus storedStatus() {
    return authorizationRepository.findAll().getFirst().getStatus();
  }

  private void advanceClock(Duration duration) {
    currentTime.updateAndGet(instant -> instant.plus(duration));
  }
}
