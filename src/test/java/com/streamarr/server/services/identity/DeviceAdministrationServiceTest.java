package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeDeviceRegistrationRepository;
import com.streamarr.server.fakes.FakeEsnBlockRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceRegistrationLifecycle;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Device administration over fakes: revocation ends sessions with the registration, a block revokes
 * every matching registration first, and the reads scope by visibility.
 */
@Tag("UnitTest")
@DisplayName("Device Administration Service Tests")
class DeviceAdministrationServiceTest {

  private final FakeDeviceRegistrationRepository registrations =
      new FakeDeviceRegistrationRepository();
  private final FakeEsnBlockRepository blocks = new FakeEsnBlockRepository();
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final DeviceAdministrationService service =
      new DeviceAdministrationService(
          authorization,
          registrations,
          blocks,
          households,
          new DeviceRegistrationLifecycle(registrations, sessions),
          audit,
          new MutationTransactions(
              new FakeTransactionManager(), new ConstraintViolationTranslator()),
          new PaginationService(),
          Clock.systemUTC());

  private UUID householdId;

  @BeforeEach
  void setUp() {
    householdId = households.save(HouseholdFixture.defaultHouseholdBuilder().build()).getId();
  }

  @Test
  @DisplayName("Should revoke the registration and sessions when the registration is active")
  void shouldRevokeRegistrationAndSessionsWhenRegistrationActive() {
    var registration = activeRegistration("esn-1");
    var session =
        sessions.save(
            AuthSession.builder()
                .accountId(UUID.randomUUID())
                .registrationId(registration.getId())
                .deviceName("tv")
                .build());

    var revoked = service.revokeDeviceRegistration(identity(), registration.getId());

    assertThat(revoked).isInstanceOf(Outcome.Accepted.class);
    assertThat(registrations.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(sessions.findById(session.getId()).orElseThrow().getRevokedAt()).isNotNull();
    assertThat(audit.entries())
        .extracting(entry -> entry.operation())
        .containsExactly("revokeDeviceRegistration");

    assertThat(rejectionOf(service.revokeDeviceRegistration(identity(), registration.getId())))
        .isInstanceOf(DeviceRejections.RegistrationNotActive.class);
  }

  @Test
  @DisplayName("Should return not found when the registration is hidden")
  void shouldReturnNotFoundWhenRegistrationHidden() {
    var registration = activeRegistration("esn-1");
    authorization.denyAll();

    assertThat(rejectionOf(service.revokeDeviceRegistration(identity(), registration.getId())))
        .isInstanceOf(DeviceRejections.RegistrationNotFound.class);
  }

  @Test
  @DisplayName("Should revoke matching registrations before blocking when the ESN is valid")
  void shouldRevokeMatchesBeforeBlockingWhenEsnValid() {
    var registration = activeRegistration("esn-1");
    var session =
        sessions.save(
            AuthSession.builder()
                .accountId(UUID.randomUUID())
                .registrationId(registration.getId())
                .deviceName("tv")
                .build());

    assertThat(rejectionOf(service.blockEsn(identity(), householdId, " ", "stolen")))
        .isInstanceOf(DeviceRejections.EsnRequired.class);
    assertThat(rejectionOf(service.blockEsn(identity(), householdId, "esn-1", " ")))
        .isInstanceOf(DeviceRejections.ReasonRequired.class);
    assertThat(rejectionOf(service.blockEsn(identity(), UUID.randomUUID(), "esn-1", "stolen")))
        .isInstanceOf(DeviceRejections.HouseholdNotFound.class);

    var blocked = service.blockEsn(identity(), householdId, "esn-1", "stolen");

    assertThat(blocked).isInstanceOf(Outcome.Accepted.class);
    assertThat(registrations.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(sessions.findById(session.getId()).orElseThrow().getRevokedAt()).isNotNull();
    assertThat(blocks.existsByEsnAndHouseholdId("esn-1", householdId)).isTrue();
    assertThat(audit.entries()).extracting(entry -> entry.operation()).containsExactly("blockEsn");
  }

  @Test
  @DisplayName("Should require an ESN when creating a server-wide block")
  void shouldRequireEsnWhenCreatingServerWideBlock() {
    assertThat(rejectionOf(service.blockEsnServerWide(identity(), " ", "stolen")))
        .isInstanceOf(DeviceRejections.EsnRequired.class);
  }

  @Test
  @DisplayName("Should require a reason when creating a server-wide block")
  void shouldRequireReasonWhenCreatingServerWideBlock() {
    assertThat(rejectionOf(service.blockEsnServerWide(identity(), "esn-1", " ")))
        .isInstanceOf(DeviceRejections.ReasonRequired.class);
  }

  @Test
  @DisplayName("Should require an ESN when removing a Household block")
  void shouldRequireEsnWhenRemovingHouseholdBlock() {
    assertThat(rejectionOf(service.unblockEsn(identity(), householdId, " ")))
        .isInstanceOf(DeviceRejections.EsnRequired.class);
  }

  @Test
  @DisplayName("Should require an ESN when removing a server-wide block")
  void shouldRequireEsnWhenRemovingServerWideBlock() {
    assertThat(rejectionOf(service.unblockEsnServerWide(identity(), " ")))
        .isInstanceOf(DeviceRejections.EsnRequired.class);
  }

  @Test
  @DisplayName("Should reject a duplicate Household ESN block as already blocked")
  void shouldRejectDuplicateHouseholdEsnBlockAsAlreadyBlocked() {
    assertThat(service.blockEsn(identity(), householdId, "esn-1", "first"))
        .isInstanceOf(Outcome.Accepted.class);

    assertThat(rejectionOf(service.blockEsn(identity(), householdId, "esn-1", "second")))
        .isInstanceOf(DeviceRejections.AlreadyBlocked.class);
  }

  @Test
  @DisplayName("Should reject a duplicate server-wide ESN block as already blocked")
  void shouldRejectDuplicateServerWideEsnBlockAsAlreadyBlocked() {
    assertThat(service.blockEsnServerWide(identity(), "esn-1", "first"))
        .isInstanceOf(Outcome.Accepted.class);

    assertThat(rejectionOf(service.blockEsnServerWide(identity(), "esn-1", "second")))
        .isInstanceOf(DeviceRejections.AlreadyBlocked.class);
  }

  @Test
  @DisplayName("Should allow the server-wide block when the fresh ceremony is complete")
  void shouldAllowServerWideBlockWhenFreshCeremonyComplete() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.BlockEsnServerWide
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : allowed());
    assertThat(rejectionOf(service.blockEsnServerWide(identity(), "esn-1", "stolen")))
        .isInstanceOf(DeviceRejections.ReauthenticationRequired.class);

    authorization.allowAll();
    var registration = activeRegistration("esn-1");
    var session =
        sessions.save(
            AuthSession.builder()
                .accountId(UUID.randomUUID())
                .registrationId(registration.getId())
                .deviceName("tv")
                .build());
    var blocked = service.blockEsnServerWide(identity(), "esn-1", "stolen");
    assertThat(blocked).isInstanceOf(Outcome.Accepted.class);
    assertThat(registrations.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(sessions.findById(session.getId()).orElseThrow().getRevokedAt()).isNotNull();
    assertThat(blocks.existsByEsnAndHouseholdIdIsNull("esn-1")).isTrue();
  }

  @Test
  @DisplayName("Should remove only the named scope when unblocking an ESN")
  void shouldRemoveOnlyNamedScopeWhenUnblockingEsn() {
    blocks.save(EsnBlock.builder().esn("esn-1").householdId(householdId).reason("old").build());
    blocks.save(EsnBlock.builder().esn("esn-1").reason("server-wide").build());

    assertThat(rejectionOf(service.unblockEsn(identity(), householdId, "esn-9")))
        .isInstanceOf(DeviceRejections.BlockNotFound.class);

    assertThat(service.unblockEsn(identity(), householdId, "esn-1"))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(blocks.existsByEsnAndHouseholdId("esn-1", householdId)).isFalse();
    assertThat(blocks.existsByEsnAndHouseholdIdIsNull("esn-1")).isTrue();

    assertThat(service.unblockEsnServerWide(identity(), "esn-1"))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(blocks.existsByEsnAndHouseholdIdIsNull("esn-1")).isFalse();
  }

  @Test
  @DisplayName("Should return only visible rows when reading device administration")
  void shouldReturnOnlyVisibleRowsWhenReadingDeviceAdministration() {
    activeRegistration("esn-1");
    blocks.save(EsnBlock.builder().esn("esn-2").householdId(householdId).reason("x").build());
    blocks.save(EsnBlock.builder().esn("esn-3").reason("server-wide").build());

    assertThat(service.householdDevices(identity(), householdId, paginationOptions()).items())
        .hasSize(1);
    assertThat(service.esnBlocks(identity(), householdId, paginationOptions()).items()).hasSize(1);
    assertThat(service.serverEsnBlocks(identity(), paginationOptions()).items()).hasSize(1);

    authorization.denyAll();
    assertThat(service.householdDevices(identity(), householdId, paginationOptions()).items())
        .isEmpty();
    assertThat(service.esnBlocks(identity(), householdId, paginationOptions()).items()).isEmpty();
    assertThat(service.serverEsnBlocks(identity(), paginationOptions()).items()).isEmpty();
  }

  @Test
  @DisplayName("Should page device administration reads by stable keyset")
  void shouldPageDeviceAdministrationReadsByStableKeyset() {
    var newest = activeRegistration("esn-newest");
    var oldest = activeRegistration("esn-oldest");
    AuditFieldSetter.setCreatedOn(newest, Instant.parse("2026-08-22T12:00:00Z"));
    AuditFieldSetter.setCreatedOn(oldest, Instant.parse("2026-08-21T12:00:00Z"));

    var firstPage = service.householdDevices(identity(), householdId, paginationOptions(null, 1));
    var secondPage =
        service.householdDevices(identity(), householdId, paginationOptions(newest.getId(), 1));

    assertThat(firstPage.items())
        .extracting(item -> item.item().getEsn())
        .containsExactly("esn-newest");
    assertThat(firstPage.hasNextPage()).isTrue();
    assertThat(secondPage.items())
        .extracting(item -> item.item().getEsn())
        .containsExactly("esn-oldest");
    assertThat(secondPage.hasPreviousPage()).isTrue();
  }

  @Test
  @DisplayName("Should fail closed when authorization cannot decide a device read")
  void shouldFailClosedWhenAuthorizationCannotDecideDeviceRead() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var currentIdentity = identity();
    var options = paginationOptions();

    assertThatThrownBy(() -> service.householdDevices(currentIdentity, householdId, options))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThatThrownBy(() -> service.esnBlocks(currentIdentity, householdId, options))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThatThrownBy(() -> service.serverEsnBlocks(currentIdentity, options))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should fail closed when authorization cannot decide a device mutation")
  void shouldFailClosedWhenAuthorizationCannotDecideDeviceMutation() {
    var registration = activeRegistration("esn-1");
    blocks.save(EsnBlock.builder().esn("esn-2").householdId(householdId).reason("x").build());
    blocks.save(EsnBlock.builder().esn("esn-3").reason("x").build());
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var currentIdentity = identity();
    var registrationId = registration.getId();

    assertThatThrownBy(() -> service.revokeDeviceRegistration(currentIdentity, registrationId))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThatThrownBy(() -> service.blockEsn(currentIdentity, householdId, "esn-4", "x"))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThatThrownBy(() -> service.blockEsnServerWide(currentIdentity, "esn-4", "x"))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThatThrownBy(() -> service.unblockEsn(currentIdentity, householdId, "esn-2"))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThatThrownBy(() -> service.unblockEsnServerWide(currentIdentity, "esn-3"))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  private DeviceRegistration activeRegistration(String esn) {
    return registrations.save(
        DeviceRegistration.builder()
            .esn(esn)
            .displayName("Living Room TV")
            .householdId(householdId)
            .authorizingAccountId(UUID.randomUUID())
            .build());
  }

  private static KeysetPaginationOptions paginationOptions() {
    return paginationOptions(null, 100);
  }

  private static KeysetPaginationOptions paginationOptions(UUID cursorId, int limit) {
    return new KeysetPaginationOptions(
        cursorId,
        PaginationOptions.builder()
            .paginationDirection(PaginationDirection.FORWARD)
            .cursor(Optional.empty())
            .limit(limit)
            .build());
  }

  private AuthenticatedIdentity identity() {
    return authorization.currentIdentity();
  }

  private static Decision<AuthorizationUnit> allowed() {
    return new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
  }

  private static Object rejectionOf(Outcome<?, ?> outcome) {
    return switch (outcome) {
      case Outcome.Rejected<?, ?>(var rejections) -> rejections.getFirst();
      case Outcome.Accepted<?, ?> accepted ->
          throw new AssertionError("expected a rejection but got " + accepted);
    };
  }
}
