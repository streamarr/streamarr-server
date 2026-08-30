package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.EsnBlockedException;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeDeviceAuthorizationRepository;
import com.streamarr.server.fakes.FakeDeviceRegistrationRepository;
import com.streamarr.server.fakes.FakeEsnBlockRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceAuthorizationService;
import com.streamarr.server.services.auth.DeviceAuthorizationServiceHarness;
import com.streamarr.server.services.auth.DeviceDecision;
import com.streamarr.server.services.identity.DevicePairingService.EligibleHouseholdDetails;
import com.streamarr.server.services.identity.DevicePairingService.PairingDecisionCommand;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * The approval ceremony over fakes: the grant resolves before Cedar, the chosen Household must be
 * usable by the approver, and a blocked ESN refuses before any decision is written.
 */
@Tag("UnitTest")
@DisplayName("Device Pairing Service Tests")
class DevicePairingServiceTest {

  private final Clock clock = Clock.systemUTC();
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

    var lookup = service.lookup(identity(), issued.userCode());

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

  private PairingDecisionCommand approve(String userCode, UUID householdId) {
    return PairingDecisionCommand.builder()
        .userCode(userCode)
        .decision(DeviceDecision.APPROVE)
        .householdId(householdId)
        .build();
  }

  private AuthenticatedIdentity identity() {
    return authorization.currentIdentity();
  }
}
