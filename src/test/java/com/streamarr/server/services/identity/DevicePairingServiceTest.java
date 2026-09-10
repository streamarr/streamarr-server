package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.CredentialAttemptMetadata;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.DeviceCodeExpiredException;
import com.streamarr.server.exceptions.DeviceCodeNotFoundException;
import com.streamarr.server.exceptions.EsnBlockedException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.InvalidUserCodeException;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.fakes.FakeDeviceAuthorizationRepository;
import com.streamarr.server.fakes.FakeDeviceRegistrationRepository;
import com.streamarr.server.fakes.FakeEsnBlockRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceAuthorizationService;
import com.streamarr.server.services.auth.DeviceAuthorizationServiceHarness;
import com.streamarr.server.services.auth.DeviceDecision;
import com.streamarr.server.services.identity.DevicePairingService.EligibleHouseholdDetails;
import com.streamarr.server.services.identity.DevicePairingService.PairingDecisionCommand;
import com.streamarr.server.services.identity.DevicePairingService.PairingLookupCommand;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pairing approval tested with fakes: the grant resolves before Cedar, the chosen Household must be
 * usable by the approver, and a blocked ESN refuses before any decision is written.
 */
@Tag("UnitTest")
@DisplayName("Device Pairing Service Tests")
class DevicePairingServiceTest {

  private final MutableClock clock = new MutableClock();
  private final FakeDeviceAuthorizationRepository authorizations =
      new FakeDeviceAuthorizationRepository();
  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeDeviceRegistrationRepository registrations =
      new FakeDeviceRegistrationRepository();
  private final FakeEsnBlockRepository blocks = new FakeEsnBlockRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeRefreshTokenRepository tokens = new FakeRefreshTokenRepository();
  private final FakeCredentialAttemptRepository credentialAttempts =
      new FakeCredentialAttemptRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final DeviceAuthorizationService deviceAuthorizationService =
      DeviceAuthorizationServiceHarness.harness()
          .authorizations(authorizations)
          .accounts(accounts)
          .registrations(registrations)
          .esnBlocks(blocks)
          .sessions(sessions)
          .tokens(tokens)
          .credentialAttempts(credentialAttempts)
          .clock(clock)
          .build();

  private final DevicePairingService service =
      new DevicePairingService(
          authorization, deviceAuthorizationService, accounts, households, blocks);

  private UserAccount approver;
  private UUID visitedHouseholdId;

  @BeforeEach
  void setUp() {
    var home = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    approver =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .id(authorization.currentIdentity().accountId())
                .householdId(home.getId())
                .build());
    visitedHouseholdId =
        households.save(HouseholdFixture.defaultHouseholdBuilder().name("Cabin").build()).getId();
    shares.share(approver.getPersonalProfileId(), visitedHouseholdId, false);
  }

  @Test
  @DisplayName("Should show the device and Households when the approver looks up the code")
  void shouldShowDeviceAndHouseholdsWhenApproverLooksUpCode() {
    var issued = deviceAuthorizationService.issue("Living Room TV", "esn-1");

    var lookup =
        service.lookup(
            identity(),
            PairingLookupCommand.builder()
                .userCode(issued.userCode())
                .ipAddress("192.0.2.30")
                .build());

    assertThat(lookup.authorization().deviceName()).isEqualTo("Living Room TV");
    assertThat(lookup.households())
        .extracting(EligibleHouseholdDetails::id)
        .containsExactly(approver.getHouseholdId(), visitedHouseholdId);
  }

  @Test
  @DisplayName("Should bind the approval when the Household is usable by the approver")
  void shouldBindApprovalWhenHouseholdUsableByApprover() {
    var issued = deviceAuthorizationService.issue("TV", "esn-1");
    var code = issued.userCode();

    var caller = identity();
    var withoutHousehold = approve(code, null);
    assertThatThrownBy(() -> service.decide(caller, withoutHousehold))
        .isInstanceOf(HouseholdRequiredException.class);
    var strangersHousehold = approve(code, UUID.randomUUID());
    assertThatThrownBy(() -> service.decide(caller, strangersHousehold))
        .isInstanceOf(HouseholdAccessDeniedException.class);

    var view = service.decide(identity(), approve(code, visitedHouseholdId));

    assertThat(view.status()).isEqualTo(DeviceAuthorizationStatus.APPROVED);
    assertThat(authorizations.findAll().getFirst().getChosenHouseholdId())
        .isEqualTo(visitedHouseholdId);
  }

  @Test
  @DisplayName("Should reject approval when the ESN is blocked in either scope")
  void shouldRejectApprovalWhenEsnBlockedInEitherScope() {
    blocks.save(
        EsnBlock.builder().esn("esn-1").householdId(visitedHouseholdId).reason("x").build());
    var scopedApproval =
        approve(deviceAuthorizationService.issue("TV", "esn-1").userCode(), visitedHouseholdId);
    var caller = identity();
    assertThatThrownBy(() -> service.decide(caller, scopedApproval))
        .isInstanceOf(EsnBlockedException.class);

    blocks.save(EsnBlock.builder().esn("esn-2").reason("server-wide").build());
    var serverWideApproval =
        approve(
            deviceAuthorizationService.issue("TV", "esn-2").userCode(), approver.getHouseholdId());
    assertThatThrownBy(() -> service.decide(caller, serverWideApproval))
        .isInstanceOf(EsnBlockedException.class);
  }

  @Test
  @DisplayName("Should deny without a Household and require Cedar when pairing is decided")
  void shouldDenyWithoutHouseholdAndRequireCedarWhenPairingDecided() {
    var denied = deviceAuthorizationService.issue("TV", "esn-1").userCode();
    var view =
        service.decide(
            identity(),
            PairingDecisionCommand.builder()
                .userCode(denied)
                .decision(DeviceDecision.DENY)
                .ipAddress("192.0.2.30")
                .build());
    assertThat(view.status()).isEqualTo(DeviceAuthorizationStatus.DENIED);

    authorization.denyAll();
    var gatedApproval =
        approve(
            deviceAuthorizationService.issue("TV", "esn-3").userCode(), approver.getHouseholdId());
    var gatedCaller = identity();
    assertThatThrownBy(() -> service.decide(gatedCaller, gatedApproval))
        .isInstanceOf(AccessDeniedException.class);
  }

  @ParameterizedTest(name = "successful decision={0}")
  @ValueSource(booleans = {false, true})
  @DisplayName("Should preserve the shared approver budget when lookup or decision succeeds")
  void shouldPreserveSharedApproverBudgetWhenLookupOrDecisionSucceeds(boolean successfulDecision) {
    var issued = deviceAuthorizationService.issue("TV", "esn-budget");
    var caller = identity();
    var missingLookup = lookup("BBBB-BBBB");
    var missingDecision = deny("BBBB-BBBB");
    var knownLookup = lookup(issued.userCode());
    var knownDecision = deny(issued.userCode());
    for (var i = 0; i < 2; i++) {
      assertThatThrownBy(() -> service.lookup(caller, missingLookup))
          .isInstanceOf(DeviceCodeNotFoundException.class);
      assertThatThrownBy(() -> service.decide(caller, missingDecision))
          .isInstanceOf(DeviceCodeNotFoundException.class);
    }

    if (successfulDecision) {
      assertThat(service.decide(caller, knownDecision).status())
          .isEqualTo(DeviceAuthorizationStatus.DENIED);
    } else {
      assertThat(service.lookup(caller, knownLookup).authorization().deviceName()).isEqualTo("TV");
    }

    clock.advance(Duration.ofSeconds(1));
    assertThatThrownBy(() -> service.lookup(caller, missingLookup))
        .isInstanceOf(DeviceCodeNotFoundException.class);
    assertThatThrownBy(() -> service.lookup(caller, knownLookup))
        .isInstanceOf(TooManyDeviceAttemptsException.class);
    assertThatThrownBy(() -> service.decide(caller, knownDecision))
        .isInstanceOf(TooManyDeviceAttemptsException.class);
    assertThat(credentialAttempts.attempts())
        .hasSize(6)
        .allSatisfy(
            attempt -> assertThat(attempt.metadata().accountId()).isEqualTo(approver.getId()));
    var anotherApprover = AuthenticatedIdentityFixture.accountScopedBuilder().build();
    assertThat(
            service.lookup(anotherApprover, lookup(issued.userCode())).authorization().deviceName())
        .isEqualTo("TV");
  }

  @Test
  @DisplayName("Should release completed slots when successful presentations exceed five")
  void shouldReleaseCompletedSlotsWhenSuccessfulPresentationsExceedFive() {
    for (var i = 0; i < 6; i++) {
      var issued = deviceAuthorizationService.issue("TV-" + i, "esn-" + i);
      assertThat(service.lookup(identity(), lookup(issued.userCode())).authorization().deviceName())
          .isEqualTo("TV-" + i);
      assertThat(service.decide(identity(), deny(issued.userCode())).status())
          .isEqualTo(DeviceAuthorizationStatus.DENIED);
    }

    assertThat(credentialAttempts.attempts())
        .hasSize(12)
        .allSatisfy(
            attempt -> assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.SUCCEEDED));
  }

  @Test
  @DisplayName("Should journal no attempt when a pairing code is malformed")
  void shouldJournalNoAttemptWhenPairingCodeIsMalformed() {
    var caller = identity();
    var invalidLookup = lookup("invalid");
    var invalidDecision = deny("invalid");
    assertThatThrownBy(() -> service.lookup(caller, invalidLookup))
        .isInstanceOf(InvalidUserCodeException.class);
    assertThatThrownBy(() -> service.decide(caller, invalidDecision))
        .isInstanceOf(InvalidUserCodeException.class);
    assertThat(credentialAttempts.attempts()).isEmpty();
  }

  @Test
  @DisplayName("Should distinguish decision expiry from a lookup miss when the grant has expired")
  void shouldDistinguishDecisionExpiryFromLookupMissWhenGrantHasExpired() {
    var issued = deviceAuthorizationService.issue("TV", "esn-expired");
    clock.advance(Duration.ofDays(1));
    var caller = identity();
    var expiredLookup = lookup(issued.userCode());
    var expiredDecision = deny(issued.userCode());
    assertThatThrownBy(() -> service.lookup(caller, expiredLookup))
        .isInstanceOf(DeviceCodeNotFoundException.class);
    assertThatThrownBy(() -> service.decide(caller, expiredDecision))
        .isInstanceOf(DeviceCodeExpiredException.class);
    assertThat(credentialAttempts.attempts())
        .hasSize(2)
        .allSatisfy(
            attempt -> assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.FAILED));
  }

  private PairingLookupCommand lookup(String code) {
    return PairingLookupCommand.builder().userCode(code).ipAddress("192.0.2.30").build();
  }

  private PairingDecisionCommand deny(String code) {
    return PairingDecisionCommand.builder()
        .userCode(code)
        .decision(DeviceDecision.DENY)
        .ipAddress("192.0.2.30")
        .build();
  }

  private PairingDecisionCommand approve(String userCode, UUID householdId) {
    return PairingDecisionCommand.builder()
        .userCode(userCode)
        .decision(DeviceDecision.APPROVE)
        .householdId(householdId)
        .ipAddress("192.0.2.30")
        .build();
  }

  private AuthenticatedIdentity identity() {
    return authorization.currentIdentity();
  }

  @Test
  @DisplayName("Should journal one attempt against the approver when a code is looked up")
  void shouldJournalOneAttemptAgainstApproverWhenCodeIsLookedUp() {
    var issued = deviceAuthorizationService.issue("Living Room TV", "esn-1");

    service.lookup(
        identity(),
        PairingLookupCommand.builder().userCode(issued.userCode()).ipAddress("192.0.2.30").build());

    assertThat(credentialAttempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.metadata())
                  .isEqualTo(
                      CredentialAttemptMetadata.builder()
                          .kind(CredentialKind.DEVICE_PAIRING_CODE)
                          .accountId(approver.getId())
                          .ipAddress("192.0.2.30")
                          .build());
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.SUCCEEDED);
            });
  }

  @Test
  @DisplayName("Should journal exactly one attempt when a code is decided")
  void shouldJournalExactlyOneAttemptWhenCodeIsDecided() {
    var issued = deviceAuthorizationService.issue("Living Room TV", "esn-2");

    service.decide(identity(), approve(issued.userCode(), approver.getHouseholdId()));

    // resolveForDecision journals the presentation; the decision write records nothing more.
    assertThat(credentialAttempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.metadata().accountId()).isEqualTo(approver.getId());
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.SUCCEEDED);
            });
  }
}
