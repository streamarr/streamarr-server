package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.CanonicalBaseUrl;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.DeviceAuthProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.auth.DeviceAuthorization;
import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.DeviceCodeExpiredException;
import com.streamarr.server.exceptions.DeviceCodeNotFoundException;
import com.streamarr.server.exceptions.DeviceCodeNotPendingException;
import com.streamarr.server.exceptions.DevicePairingNotConfiguredException;
import com.streamarr.server.exceptions.EsnRequiredException;
import com.streamarr.server.exceptions.InvalidUserCodeException;
import com.streamarr.server.exceptions.SetupIncompleteException;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeDeviceAuthorizationRepository;
import com.streamarr.server.fakes.FakeDeviceRegistrationRepository;
import com.streamarr.server.fakes.FakeEsnBlockRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.FakeServerBootstrapRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.DeviceAuthorizationDecisionCommand;
import com.streamarr.server.repositories.auth.DeviceAuthorizationInsertCommand;
import com.streamarr.server.repositories.auth.DeviceAuthorizationInsertResult;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import com.streamarr.server.repositories.auth.DeviceCodeCollisionException;
import com.streamarr.server.repositories.auth.UserCodeCollisionException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
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
  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeUserAccountRepository userAccountRepository =
      new FakeUserAccountRepository(shares);
  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeDeviceRegistrationRepository registrationRepository =
      new FakeDeviceRegistrationRepository();
  private final FakeEsnBlockRepository esnBlockRepository = new FakeEsnBlockRepository();
  private final FakeServerBootstrapRepository serverBootstrapRepository = claimedBootstrap();
  private final FakeRefreshTokenRepository tokenRepository = new FakeRefreshTokenRepository();

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

  private final AccessTokenIssuer accessTokenIssuer =
      new AccessTokenIssuer(
          cryptoConfig.jwtEncoder(cryptoConfig.tokenSigningKeys(tokenProperties)),
          tokenProperties,
          clock);

  private DeviceAuthorizationService service;
  private UserAccount approver;

  @BeforeEach
  void createService() {
    service = serviceWith(CanonicalBaseUrl.of(BASE_URL, false));
    approver = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
  }

  @Test
  @DisplayName("Should return an absolute verification URI and server timings when issuing a code")
  void shouldReturnAbsoluteVerificationUriAndServerTimingsWhenIssuingCode() {
    var issued = service.issue("Apple TV", "esn-1");

    assertThat(issued.verificationUri()).isEqualTo(BASE_URL + "/link");
    assertThat(issued.interval()).isEqualTo(5);
    assertThat(issued.expiresIn()).isEqualTo(600);
    assertThat(issued.userCode()).matches("[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}");
    assertThat(issued.deviceCode()).hasSize(43);
  }

  @Test
  @DisplayName("Should return non-default timings when custom values are configured")
  void shouldReturnNonDefaultTimingsWhenCustomValuesConfigured() {
    var configuredProperties =
        DeviceAuthProperties.builder()
            .codeTtl(Duration.ofMinutes(17))
            .pollIntervalSeconds(37)
            .verificationPath(properties.verificationPath())
            .maxOutstandingCodes(properties.maxOutstandingCodes())
            .maxGuessAttempts(properties.maxGuessAttempts())
            .guessWindow(properties.guessWindow())
            .sweepInterval(properties.sweepInterval())
            .build();
    var configuredService = serviceWith(configuredProperties);

    var issued = configuredService.issue("Apple TV", "esn-1");

    assertThat(issued.interval()).isEqualTo(37);
    assertThat(issued.expiresIn()).isEqualTo(Duration.ofMinutes(17).toSeconds());
  }

  @Test
  @DisplayName("Should return a canonical 256-bit base64url device code when issuing a grant")
  void shouldReturnCanonical256BitBase64urlDeviceCodeWhenIssuingGrant() {
    var issued = service.issue("Apple TV", "esn-1");

    var decoded = Base64.getUrlDecoder().decode(issued.deviceCode());

    assertThat(decoded).hasSize(32);
    assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded))
        .isEqualTo(issued.deviceCode());
  }

  @Test
  @DisplayName("Should store only the SHA-256 digest when persisting a device code")
  void shouldStoreOnlySha256DigestWhenPersistingDeviceCode() throws Exception {
    var issued = service.issue("Apple TV", "esn-1");
    var expectedDigest =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(issued.deviceCode().getBytes(StandardCharsets.UTF_8)));

    assertThat(authorizationRepository.findAll())
        .singleElement()
        .extracting(DeviceAuthorization::getDeviceCodeDigest)
        .isEqualTo(expectedDigest);
  }

  @Test
  @DisplayName("Should refuse issuance when no canonical base URL is configured")
  void shouldRefuseIssuanceWhenNoCanonicalBaseUrlConfigured() {
    var unconfigured = serviceWith(CanonicalBaseUrl.absent());

    assertThat(unconfigured.isPairingEnabled()).isFalse();
    assertThatThrownBy(() -> unconfigured.issue("Apple TV", "esn-1"))
        .isInstanceOf(DevicePairingNotConfiguredException.class);
  }

  @Test
  @DisplayName("Should let an existing grant finish when new issuance is disabled")
  void shouldLetExistingGrantFinishWhenNewIssuanceDisabled() {
    var issued = service.issue("Apple TV", "esn-1");

    // Only new issuance is gated; a code already shown to a person must never be stranded.
    var unconfigured = serviceWith(CanonicalBaseUrl.absent());
    assertThat(unconfigured.lookup(issued.userCode(), approver.getId()).status())
        .isEqualTo(DeviceAuthorizationStatus.PENDING);
    unconfigured.decide(decisionCommand(issued.userCode()));

    assertThat(unconfigured.redeem(issued.deviceCode()))
        .isInstanceOf(DevicePollResult.Success.class);
  }

  @Test
  @DisplayName("Should report pending when the code has not been approved")
  void shouldReportPendingWhenCodeNotApproved() {
    var issued = service.issue("Apple TV", "esn-1");

    advanceClock(Duration.ofSeconds(5));

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Pending.class);
  }

  /**
   * RFC 8628 §3.2 defines the interval as the wait <em>between</em> polling requests. Nothing
   * precedes the first one, so a device that polls the moment it holds a code is conforming, and
   * charging it a permanent cumulative penalty would halve how often it notices its own approval.
   */
  @Test
  @DisplayName(
      "Should allow an immediate poll and leave the interval untouched when no poll preceded it")
  void shouldAllowImmediatePollAndLeaveIntervalUntouchedWhenNoPollPrecededIt() {
    var issued = service.issue("Apple TV", "esn-1");

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Pending.class);
    assertThat(storedInterval()).isEqualTo(5);
  }

  @Test
  @DisplayName("Should slow down the caller when a poll arrives before the cadence allows")
  void shouldSlowDownCallerWhenPollArrivesBeforeCadenceAllows() {
    var issued = service.issue("Apple TV", "esn-1");
    service.redeem(issued.deviceCode());

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.SlowDown.class);
  }

  @Test
  @DisplayName("Should raise the interval by five cumulative seconds when polls arrive early")
  void shouldRaiseIntervalByFiveCumulativeSecondsWhenPollsArriveEarly() {
    var issued = service.issue("Apple TV", "esn-1");

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
  @DisplayName("Should fail fast when the cumulative polling interval would overflow")
  void shouldFailFastWhenCumulativePollingIntervalWouldOverflow() {
    var issued = service.issue("Apple TV", "esn-1");
    var deviceCode = issued.deviceCode();
    var authorization = authorizationRepository.findAll().getFirst();
    authorization.setPollIntervalSeconds(Integer.MAX_VALUE - 4);
    authorization.setNextPollAt(clock.instant().plusSeconds(1));

    assertThatThrownBy(() -> service.redeem(deviceCode)).isInstanceOf(ArithmeticException.class);
    assertThat(storedInterval()).isEqualTo(Integer.MAX_VALUE - 4);
  }

  @Test
  @DisplayName("Should mint a session at the winning poll when the grant is approved")
  void shouldMintSessionAtWinningPollWhenGrantApproved() {
    var issued = service.issue("Apple TV", "esn-1");
    approve(issued.userCode());

    assertThat(sessionRepository.findAll()).isEmpty();

    var result = service.redeem(issued.deviceCode());

    assertThat(result).isInstanceOf(DevicePollResult.Success.class);
    assertThat(sessionRepository.findAll()).singleElement();
    assertThat(sessionRepository.findAll().getFirst().getDeviceName()).isEqualTo("Apple TV");
    assertThat(storedStatus()).isEqualTo(DeviceAuthorizationStatus.CONSUMED);
  }

  @Test
  @DisplayName("Should create an account-scoped session when the grant is redeemed at the picker")
  void shouldCreateAccountScopedSessionWhenGrantRedeemedAtPicker() {
    var issued = service.issue("Apple TV", "esn-1");
    approve(issued.userCode());

    var result = service.redeem(issued.deviceCode());

    assertThat(result)
        .isInstanceOf(DevicePollResult.Success.class)
        .extracting(success -> ((DevicePollResult.Success) success).accessToken().scope())
        .isEqualTo(TokenScope.ACCOUNT);
    assertThat(sessionRepository.findAll())
        .singleElement()
        .satisfies(
            session -> {
              assertThat(session.getContextHouseholdId()).isEqualTo(approver.getHouseholdId());
              assertThat(session.getSelectedProfileId()).isNull();
            });
  }

  @Test
  @DisplayName("Should report expired when the grant has already been consumed")
  void shouldReportExpiredWhenGrantAlreadyConsumed() {
    var issued = service.issue("Apple TV", "esn-1");
    approve(issued.userCode());
    service.redeem(issued.deviceCode());

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Expired.class);
    assertThat(sessionRepository.findAll()).hasSize(1);
  }

  @Test
  @DisplayName("Should report denial as its own terminal state when the request was denied")
  void shouldReportDenialAsOwnTerminalStateWhenRequestDenied() {
    var issued = service.issue("Apple TV", "esn-1");
    decide(issued.userCode(), DeviceDecision.DENY);

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Denied.class);
    assertThat(sessionRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should remain pending when immediately before the code lifetime ends")
  void shouldRemainPendingWhenImmediatelyBeforeCodeLifetimeEnds() {
    var issued = service.issue("Apple TV", "esn-1");

    advanceClock(properties.codeTtl().minusNanos(1));

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Pending.class);
  }

  @Test
  @DisplayName("Should report expired when the exact code lifetime boundary is reached")
  void shouldReportExpiredWhenExactCodeLifetimeBoundaryReached() {
    var issued = service.issue("Apple TV", "esn-1");

    advanceClock(properties.codeTtl());

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Expired.class);
    assertThat(sessionRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should report expired without touching the database when the device code is malformed")
  void shouldReportExpiredWithoutDatabaseAccessWhenDeviceCodeMalformed() {
    var inaccessibleRepository =
        new FakeDeviceAuthorizationRepository() {
          @Override
          public Optional<DeviceAuthorization> lockByDeviceCodeDigest(String digest) {
            throw new AssertionError("Malformed device code reached the repository.");
          }
        };
    var guardedService = serviceWith(inaccessibleRepository, new UserCodeGenerator());

    assertThat(guardedService.redeem("not-a-device-code"))
        .isInstanceOf(DevicePollResult.Expired.class);
    assertThat(guardedService.redeem(null)).isInstanceOf(DevicePollResult.Expired.class);
  }

  @Test
  @DisplayName("Should require the ESN when issuing a code")
  void shouldRequireEsnWhenIssuingCode() {
    assertThatThrownBy(() -> service.issue("Apple TV", null))
        .isInstanceOf(EsnRequiredException.class);
    assertThatThrownBy(() -> service.issue("Apple TV", " "))
        .isInstanceOf(EsnRequiredException.class);
  }

  @Test
  @DisplayName(
      "Should register the TV to the chosen Household when the winning poll consumes the grant")
  void shouldRegisterTvToChosenHouseholdWhenWinningPollConsumesGrant() {
    var household = UUID.randomUUID();
    shares.share(approver.getPersonalProfileId(), household, false);
    var issued = service.issue("Apple TV", "esn-1");
    service.decide(decisionCommandFor(issued.userCode(), household));

    var result = service.redeem(issued.deviceCode());

    assertThat(result).isInstanceOf(DevicePollResult.Success.class);
    var registration = registrationRepository.findAll().getFirst();
    assertThat(registration.getEsn()).isEqualTo("esn-1");
    assertThat(registration.getHouseholdId()).isEqualTo(household);
    assertThat(registration.getAuthorizingAccountId()).isEqualTo(approver.getId());
    assertThat(registration.getStatus()).isEqualTo(DeviceRegistrationStatus.ACTIVE);
    var session = sessionRepository.findAll().getFirst();
    assertThat(session.getRegistrationId()).isEqualTo(registration.getId());
    assertThat(session.getContextHouseholdId()).isEqualTo(household);
  }

  @Test
  @DisplayName("Should supersede the previous registration when the same ESN pairs again")
  void shouldSupersedePreviousRegistrationWhenSameEsnPairsAgain() {
    var first = service.issue("Apple TV", "esn-1");
    service.decide(decisionCommandFor(first.userCode(), approver.getHouseholdId()));
    assertThat(service.redeem(first.deviceCode())).isInstanceOf(DevicePollResult.Success.class);
    var firstRegistration = registrationRepository.findAll().getFirst();
    var firstSession = sessionRepository.findAll().getFirst();

    var second = service.issue("Apple TV", "esn-1");
    service.decide(decisionCommandFor(second.userCode(), approver.getHouseholdId()));
    assertThat(service.redeem(second.deviceCode())).isInstanceOf(DevicePollResult.Success.class);

    assertThat(registrationRepository.findById(firstRegistration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(sessionRepository.findById(firstSession.getId()).orElseThrow().getRevokedAt())
        .isNotNull();
  }

  @Test
  @DisplayName("Should answer expired when approval facts went stale before the winning poll")
  void shouldAnswerExpiredWhenApprovalFactsWentStaleBeforeWinningPoll() {
    // The chosen Household became unusable after approval.
    var household = UUID.randomUUID();
    var visit = shares.share(approver.getPersonalProfileId(), household, false);
    var issued = service.issue("Apple TV", "esn-1");
    service.decide(decisionCommandFor(issued.userCode(), household));
    visit.setStatus(ProfileShareStatus.ENDED);
    shares.save(visit);
    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Expired.class);

    // The ESN was blocked between approval and the winning poll.
    var blocked = service.issue("Apple TV", "esn-2");
    service.decide(decisionCommandFor(blocked.userCode(), approver.getHouseholdId()));
    esnBlockRepository.save(EsnBlock.builder().esn("esn-2").reason("stolen").build());
    assertThat(service.redeem(blocked.deviceCode())).isInstanceOf(DevicePollResult.Expired.class);
    assertThat(registrationRepository.findAll())
        .noneMatch(registration -> "esn-2".equals(registration.getEsn()));
  }

  @Test
  @DisplayName("Should show the requesting device when an approver looks up a pending code")
  void shouldShowRequestingDeviceWhenApproverLooksUpPendingCode() {
    var issued = service.issue("Living Room Apple TV", "esn-1");

    var view = service.lookup(issued.userCode(), approver.getId());

    assertThat(view.deviceName()).isEqualTo("Living Room Apple TV");
    assertThat(view.status()).isEqualTo(DeviceAuthorizationStatus.PENDING);
    assertThat(view.userCode()).isEqualTo(issued.userCode());
    assertThat(view.requestedAt()).isNotNull();
  }

  @Test
  @DisplayName("Should accept a typed code when case and separator formatting vary")
  void shouldAcceptTypedCodeWhenCaseAndSeparatorFormattingVary() {
    var issued = service.issue("Apple TV", "esn-1");

    assertThat(service.lookup(issued.userCode().toLowerCase(Locale.ROOT), approver.getId()))
        .isNotNull();
    assertThat(service.lookup(issued.userCode().replace("-", ""), approver.getId())).isNotNull();
  }

  @Test
  @DisplayName("Should collapse the result into not-found when lookup receives an expired code")
  void shouldCollapseResultIntoNotFoundWhenLookupReceivesExpiredCode() {
    var issued = service.issue("Apple TV", "esn-1");

    advanceClock(Duration.ofMinutes(11));

    var userCode = issued.userCode();
    var approverId = approver.getId();

    // A probe deserves no oracle detail; the poll answers expired_token for the same state.
    assertThatThrownBy(() -> service.lookup(userCode, approverId))
        .isInstanceOf(DeviceCodeNotFoundException.class);
  }

  @Test
  @DisplayName(
      "Should report expiration rather than unknown when an approver decides an expired code")
  void shouldReportExpirationRatherThanUnknownWhenApproverDecidesExpiredCode() {
    var issued = service.issue("Apple TV", "esn-1");

    advanceClock(Duration.ofMinutes(11));

    var userCode = issued.userCode();

    assertThatThrownBy(() -> approve(userCode)).isInstanceOf(DeviceCodeExpiredException.class);
  }

  @Test
  @DisplayName("Should reject a decision when the request was already decided")
  void shouldRejectDecisionWhenRequestAlreadyDecided() {
    var issued = service.issue("Apple TV", "esn-1");
    approve(issued.userCode());

    var userCode = issued.userCode();

    assertThatThrownBy(() -> decide(userCode, DeviceDecision.DENY))
        .isInstanceOf(DeviceCodeNotPendingException.class);
  }

  @Test
  @DisplayName("Should reject before lookup when the user code is malformed")
  void shouldRejectBeforeLookupWhenUserCodeMalformed() {
    var approverId = approver.getId();

    assertThatThrownBy(() -> service.lookup("NOPE", approverId))
        .isInstanceOf(InvalidUserCodeException.class);
  }

  @Test
  @DisplayName("Should echo the decision that happened when the request is decided")
  void shouldEchoActualDecisionWhenRequestDecided() {
    var issued = service.issue("Apple TV", "esn-1");

    var view = decide(issued.userCode(), DeviceDecision.DENY);

    assertThat(view.status()).isEqualTo(DeviceAuthorizationStatus.DENIED);
    assertThat(view.deviceName()).isEqualTo("Apple TV");
    assertThat(storedStatus()).isEqualTo(DeviceAuthorizationStatus.DENIED);
  }

  @Test
  @DisplayName("Should record who decided when the outcome is approval or denial")
  void shouldRecordWhoDecidedWhenOutcomeApprovalOrDenial() {
    var issued = service.issue("Apple TV", "esn-1");
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
  @DisplayName("Should refuse issuance when the outstanding-code cap is reached")
  void shouldRefuseIssuanceWhenOutstandingCodeCapReached() {
    service.issue("One", "esn-1");
    service.issue("Two", "esn-1");
    service.issue("Three", "esn-1");

    assertThatThrownBy(() -> service.issue("Four", "esn-1"))
        .isInstanceOf(TooManyDeviceAttemptsException.class)
        .satisfies(
            e ->
                assertThat(((TooManyDeviceAttemptsException) e).getRetryAfter())
                    // The moment capacity provably frees: when the oldest code expires.
                    .isEqualTo(Duration.ofMinutes(10)));
  }

  @Test
  @DisplayName("Should free issuance capacity when outstanding codes expire")
  void shouldFreeIssuanceCapacityWhenOutstandingCodesExpire() {
    service.issue("One", "esn-1");
    service.issue("Two", "esn-1");
    service.issue("Three", "esn-1");

    advanceClock(Duration.ofMinutes(11));

    assertThat(service.issue("Four", "esn-1").deviceCode()).isNotBlank();
  }

  @ParameterizedTest
  @EnumSource(DeviceDecision.class)
  @DisplayName("Should free issuance capacity when an outstanding code is decided")
  void shouldFreeIssuanceCapacityWhenOutstandingCodeDecided(DeviceDecision decision) {
    var first = service.issue("One", "esn-1");
    service.issue("Two", "esn-1");
    service.issue("Three", "esn-1");

    decide(first.userCode(), decision);

    assertThat(service.issue("Four", "esn-1").deviceCode()).isNotBlank();
  }

  @Test
  @DisplayName("Should warn only when issuance first nears and then reaches capacity")
  void shouldWarnOnlyWhenIssuanceFirstNearsAndThenReachesCapacity(CapturedOutput output) {
    var capacityService = serviceWithCapacity(4);

    capacityService.issue("One", "esn-1");
    capacityService.issue("Two", "esn-1");
    capacityService.issue("Three", "esn-1");
    capacityService.issue("Four", "esn-1");

    assertThat(output.getAll())
        .contains("issuance at 2 of 4")
        .doesNotContain("issuance at 3 of 4")
        .contains("issuance at 4 of 4");
  }

  @Test
  @DisplayName("Should base the capacity retry on the oldest expiry when the cap is reached")
  void shouldBaseCapacityRetryOnOldestExpiryWhenCapReached() {
    service.issue("Oldest", "esn-1");
    advanceClock(Duration.ofMinutes(2));
    service.issue("Middle", "esn-1");
    advanceClock(Duration.ofMinutes(2));
    service.issue("Newest", "esn-1");

    assertThatThrownBy(() -> service.issue("Refused", "esn-1"))
        .isInstanceOf(TooManyDeviceAttemptsException.class)
        .satisfies(
            failure ->
                assertThat(((TooManyDeviceAttemptsException) failure).getRetryAfter())
                    .isEqualTo(Duration.ofMinutes(6)));
  }

  @Test
  @DisplayName(
      "Should rethrow an integrity failure when it is unrelated to the user-code constraint")
  void shouldRethrowIntegrityFailureWhenUnrelatedToUserCodeConstraint() {
    var failure = constraintViolation("uq_unrelated_integrity");
    var throwingRepository =
        new FakeDeviceAuthorizationRepository() {
          @Override
          public DeviceAuthorizationInsertResult tryInsertWithinCap(
              DeviceAuthorizationInsertCommand command) {
            throw failure;
          }
        };
    var failingService = serviceWith(throwingRepository, new UserCodeGenerator());

    assertThatThrownBy(() -> failingService.issue("Apple TV", "esn-1")).isSameAs(failure);
  }

  @Test
  @DisplayName("Should retry issuance when the user-code constraint reports a collision")
  void shouldRetryIssuanceWhenUserCodeConstraintReportsCollision() {
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

    assertThat(collidingService.issue("First", "esn-1").userCode()).isEqualTo("BBBB-BBBB");
    assertThat(collidingService.issue("Second", "esn-1").userCode()).isEqualTo("CCCC-CCCC");
    assertThat(authorizationRepository.findAll()).hasSize(2);
  }

  @Test
  @DisplayName("Should retain the final device-code collision when retries are exhausted")
  void shouldRetainFinalDeviceCodeCollisionWhenRetriesExhausted() {
    var collision =
        new DeviceCodeCollisionException(
            constraintViolation("uq_device_authorization_device_code_digest"));
    var collidingRepository =
        new FakeDeviceAuthorizationRepository() {
          @Override
          public DeviceAuthorizationInsertResult tryInsertWithinCap(
              DeviceAuthorizationInsertCommand command) {
            throw collision;
          }
        };
    var collidingService = serviceWith(collidingRepository, new UserCodeGenerator());

    assertThatThrownBy(() -> collidingService.issue("Apple TV", "esn-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasCause(collision);
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
    collidingService.issue("First", "esn-1");

    assertThatThrownBy(() -> collidingService.issue("Second", "esn-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(UserCodeCollisionException.class);
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
    var issued = losingService.issue("Apple TV", "esn-1");
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
    var issued = losingService.issue("Apple TV", "esn-1");
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
    var issued = losingService.issue("Apple TV", "esn-1");
    var command = decisionCommand(issued.userCode());

    assertThatThrownBy(() -> losingService.decide(command))
        .isInstanceOf(DeviceCodeNotFoundException.class);
  }

  @Test
  @DisplayName("Should refuse the grant when its approver has since been disabled")
  void shouldRefuseGrantWhenApproverSinceDisabled() {
    var issued = service.issue("Apple TV", "esn-1");
    approve(issued.userCode());

    approver.setEnabled(false);
    userAccountRepository.save(approver);

    assertThat(service.redeem(issued.deviceCode())).isInstanceOf(DevicePollResult.Expired.class);
    assertThat(sessionRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse issuing a code before setup completes")
  void shouldRefuseIssuingCodeBeforeSetupCompletes() {
    var unclaimedService = serviceWith(new FakeServerBootstrapRepository());

    assertThatThrownBy(() -> unclaimedService.issue("Apple TV", "esn-1"))
        .isInstanceOf(SetupIncompleteException.class);
  }

  private static FakeServerBootstrapRepository claimedBootstrap() {
    var bootstrap = new FakeServerBootstrapRepository();
    bootstrap.claim(UUID.randomUUID());
    return bootstrap;
  }

  private DeviceAuthorizationService serviceWith(FakeServerBootstrapRepository bootstrap) {
    return new DeviceAuthorizationService(
        authorizationRepository,
        userAccountRepository,
        registrationRepository,
        esnBlockRepository,
        bootstrap,
        new DeviceRegistrationLifecycle(registrationRepository, sessionRepository),
        refreshTokenService,
        accessTokenIssuer,
        new UserCodeGenerator(),
        new DeviceCodeGenerator(),
        new DeviceGuessThrottle(properties, clock),
        properties,
        CanonicalBaseUrl.of(BASE_URL, false),
        clock);
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
        registrationRepository,
        esnBlockRepository,
        serverBootstrapRepository,
        new DeviceRegistrationLifecycle(registrationRepository, sessionRepository),
        refreshTokenService,
        accessTokenIssuer,
        generator,
        new DeviceCodeGenerator(),
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
    return serviceWith(capacityProperties);
  }

  private DeviceAuthorizationService serviceWith(DeviceAuthProperties configuredProperties) {
    return new DeviceAuthorizationService(
        authorizationRepository,
        userAccountRepository,
        registrationRepository,
        esnBlockRepository,
        serverBootstrapRepository,
        new DeviceRegistrationLifecycle(registrationRepository, sessionRepository),
        refreshTokenService,
        accessTokenIssuer,
        new UserCodeGenerator(),
        new DeviceCodeGenerator(),
        new DeviceGuessThrottle(configuredProperties, clock),
        configuredProperties,
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

  private DeviceDecisionCommand decisionCommandFor(String userCode, UUID householdId) {
    return DeviceDecisionCommand.builder()
        .userCode(userCode)
        .decision(DeviceDecision.APPROVE)
        .decidedByAccountId(approver.getId())
        .chosenHouseholdId(householdId)
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
