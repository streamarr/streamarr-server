package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAccountInvitationRepository;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeDeviceRegistrationRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakePasswordResetCodeRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceRegistrationLifecycle;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.identity.HouseholdTeardownService.FinalAccountChoice;
import com.streamarr.server.services.identity.HouseholdTeardownService.FinalAccountDisposition;
import com.streamarr.server.services.identity.HouseholdTeardownService.TearDownHouseholdCommand;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.PaginationDirection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * Household teardown over fakes: every other Account must already be gone, the final Account leaves
 * by its chosen disposition, and nothing — visit, registration, credential, or resident Profile —
 * outlives the Household.
 */
@Tag("UnitTest")
@DisplayName("Household Teardown Service Tests")
class HouseholdTeardownServiceTest {

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeProfileManagerRepository managers = new FakeProfileManagerRepository();
  private final FakeProfileManagerInvitationRepository managerInvitations =
      new FakeProfileManagerInvitationRepository();
  private final FakeAccountInvitationRepository accountInvitations =
      new FakeAccountInvitationRepository();
  private final FakePasswordResetCodeRepository passwordResetCodes =
      new FakePasswordResetCodeRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeDeviceRegistrationRepository registrations =
      new FakeDeviceRegistrationRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeSessionProgressRepository progress = new FakeSessionProgressRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final HouseholdTeardownService service = serviceUsing(accounts);

  private Household doomed;
  private Household refuge;
  private UserAccount refugeAnchor;

  @BeforeEach
  void setUp() {
    doomed = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    refuge = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    refugeAnchor = residentOf(refuge, HouseholdRole.ADMIN);
  }

  @Test
  @DisplayName("Should require the reason and classify the missing ceremony first")
  void shouldRequireReasonAndClassifyMissingCeremonyFirst() {
    assertThat(rejectionOf(service.tearDownHousehold(identity(), command(" ", null))))
        .isInstanceOf(TeardownRejections.ReasonRequired.class);
    assertThat(authorization.recordedIntents()).isEmpty();

    authorization.decideWith(
        intent ->
            intent instanceof Intent.TearDownHousehold
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : new Decision.Allowed<>(AuthorizationUnit.INSTANCE));
    assertThat(rejectionOf(service.tearDownHousehold(identity(), command("dispute", null))))
        .isInstanceOf(TeardownRejections.ReauthenticationRequired.class);
  }

  @Test
  @DisplayName("Should refuse until every other Account is gone and the disposition matches")
  void shouldRefuseUntilEveryOtherAccountGoneAndDispositionMatches() {
    residentOf(doomed, HouseholdRole.ADMIN);
    residentOf(doomed, HouseholdRole.MEMBER);
    assertThat(rejectionOf(service.tearDownHousehold(identity(), command("closing", null))))
        .isInstanceOf(TeardownRejections.AccountsRemain.class);

    var single = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    residentOf(single, HouseholdRole.ADMIN);
    assertThat(
            rejectionOf(
                service.tearDownHousehold(
                    identity(),
                    TearDownHouseholdCommand.builder()
                        .householdId(single.getId())
                        .reason("closing")
                        .build())))
        .isInstanceOf(TeardownRejections.FinalAccountRequired.class);

    var empty = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    assertThat(
            rejectionOf(
                service.tearDownHousehold(
                    identity(),
                    TearDownHouseholdCommand.builder()
                        .householdId(empty.getId())
                        .reason("closing")
                        .finalAccount(
                            FinalAccountDisposition.builder()
                                .choice(FinalAccountChoice.DELETE)
                                .build())
                        .build())))
        .isInstanceOf(TeardownRejections.FinalAccountUnexpected.class);
  }

  @Test
  @DisplayName("Should leave nothing behind when an empty Household is torn down")
  void shouldLeaveNothingBehindWhenEmptyHouseholdIsTornDown() {
    var orphan =
        profiles.save(ProfileFixture.defaultProfileBuilder().householdId(doomed.getId()).build());
    shares.share(orphan.getId(), doomed.getId(), false);
    var visit = shares.share(refugeAnchor.getPersonalProfileId(), doomed.getId(), false);
    var invitation =
        accountInvitations.save(
            AccountInvitation.builder()
                .recipientEmail("late@example.com")
                .householdId(doomed.getId())
                .householdName("Doomed")
                .householdRole(HouseholdRole.MEMBER)
                .profileName("Late")
                .profileKind(ProfileKind.ADULT)
                .issuerAccountId(UUID.randomUUID())
                .expiresAt(Instant.now().plusSeconds(3600))
                .publicId("pub-teardown")
                .secretDigest(new byte[] {1})
                .build());
    var registration =
        registrations.save(
            DeviceRegistration.builder()
                .esn("esn-doomed")
                .displayName("TV")
                .householdId(doomed.getId())
                .authorizingAccountId(refugeAnchor.getId())
                .build());

    var outcome = service.tearDownHousehold(identity(), command("closing shop", null));

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(households.findById(doomed.getId())).isEmpty();
    assertThat(profiles.findById(orphan.getId())).isEmpty();
    assertThat(shares.findById(visit.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.ENDED);
    assertThat(accountInvitations.findById(invitation.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(registrations.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(audit.entries())
        .extracting(entry -> entry.operation())
        .containsExactly("tearDownHousehold");
  }

  @Test
  @DisplayName("Should dispose of the final Account by transfer before the Household falls")
  void shouldDisposeOfFinalAccountByTransferBeforeHouseholdFalls() {
    var lastResident = residentOf(doomed, HouseholdRole.ADMIN);

    var outcome =
        service.tearDownHousehold(
            identity(),
            command(
                "closing",
                FinalAccountDisposition.builder()
                    .choice(FinalAccountChoice.TRANSFER)
                    .destinationHouseholdId(refuge.getId())
                    .build()));

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(households.findById(doomed.getId())).isEmpty();
    var moved = accounts.findById(lastResident.getId()).orElseThrow();
    assertThat(moved.getHouseholdId()).isEqualTo(refuge.getId());
    assertThat(profiles.findById(moved.getPersonalProfileId()).orElseThrow().getHouseholdId())
        .isEqualTo(refuge.getId());
  }

  @Test
  @DisplayName("Should keep the final person's Profile only behind a destination anchor")
  void shouldKeepFinalPersonsProfileOnlyBehindDestinationAnchor() {
    var lastResident = residentOf(doomed, HouseholdRole.ADMIN);

    assertThat(
            rejectionOf(
                service.tearDownHousehold(
                    identity(),
                    command(
                        "closing",
                        FinalAccountDisposition.builder()
                            .choice(FinalAccountChoice.DELETE_KEEPING_PROFILE)
                            .destinationHouseholdId(refuge.getId())
                            .build()))))
        .isInstanceOf(TeardownRejections.ReplacementManagerRequired.class);

    var outcome =
        service.tearDownHousehold(
            identity(),
            command(
                "closing",
                FinalAccountDisposition.builder()
                    .choice(FinalAccountChoice.DELETE_KEEPING_PROFILE)
                    .destinationHouseholdId(refuge.getId())
                    .replacementManagerAccountId(refugeAnchor.getId())
                    .build()));

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(lastResident.getId())).isEmpty();
    var preserved = profiles.findById(lastResident.getPersonalProfileId()).orElseThrow();
    assertThat(preserved.getHouseholdId()).isEqualTo(refuge.getId());
    assertThat(managers.existsByAccountIdAndProfileId(refugeAnchor.getId(), preserved.getId()))
        .isTrue();
    assertThat(households.findById(doomed.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should preview what teardown will take with it")
  void shouldPreviewWhatTeardownWillTakeWithIt() {
    residentOf(doomed, HouseholdRole.ADMIN);
    profiles.save(
        ProfileFixture.defaultProfileBuilder().householdId(doomed.getId()).name("Kept?").build());
    shares.share(refugeAnchor.getPersonalProfileId(), doomed.getId(), false);

    var preflight = service.teardownPreflight(identity(), doomed.getId()).orElseThrow();

    assertThat(preflight.accountCount()).isEqualTo(1);
    assertThat(preflight.unlinkedProfiles()).hasSize(1);
    assertThat(preflight.hostedVisitCount()).isEqualTo(1);

    authorization.denyAll();
    assertThat(service.teardownPreflight(identity(), doomed.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should gate the audit as a whole surface and scope activity by visibility")
  void shouldGateAuditAsWholeSurfaceAndScopeActivityByVisibility() {
    audit.append(
        SecurityAuditEntry.builder()
            .operation("somethingAudited")
            .actorAccountId(identity().accountId())
            .build());
    assertThat(
            service.securityAuditEvents(
                identity(),
                HouseholdTeardownService.SecurityAuditPageRequest.builder()
                    .direction(PaginationDirection.FORWARD)
                    .limit(10)
                    .build()))
        .hasSize(1);

    var profileId = UUID.randomUUID();
    progress.save(
        SessionProgress.builder()
            .sessionId(UUID.randomUUID())
            .profileId(profileId)
            .mediaFileId(UUID.randomUUID())
            .positionSeconds(60)
            .percentComplete(10.0)
            .durationSeconds(600)
            .build());
    assertThat(service.profileActivity(identity(), profileId)).hasSize(1);

    authorization.denyAll();
    assertThat(service.profileActivity(identity(), profileId)).isEmpty();
    var identity = identity();
    assertThatThrownBy(
            () ->
                service.securityAuditEvents(
                    identity,
                    HouseholdTeardownService.SecurityAuditPageRequest.builder()
                        .direction(PaginationDirection.FORWARD)
                        .limit(10)
                        .build()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should surface unavailable authorization from every point decision")
  void shouldSurfaceUnavailableAuthorizationFromEveryPointDecision() {
    assertAll(
        () -> {
          authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
          assertThatThrownBy(() -> service.teardownPreflight(identity(), doomed.getId()))
              .isInstanceOf(AuthorizationUnavailableException.class);
        },
        () -> {
          authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
          assertThatThrownBy(() -> service.profileActivity(identity(), UUID.randomUUID()))
              .isInstanceOf(AuthorizationUnavailableException.class);
        },
        () -> {
          authorization.decideWith(
              intent ->
                  intent instanceof Intent.TearDownHousehold
                      ? new Decision.Denied<>(Decision.DenialReason.POLICY)
                      : new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE));
          assertThatThrownBy(() -> service.tearDownHousehold(identity(), command("closing", null)))
              .isInstanceOf(AuthorizationUnavailableException.class);
        });
  }

  @Test
  @DisplayName("Should allow only one concurrent final-Account disposition")
  void shouldAllowOnlyOneConcurrentFinalAccountDisposition() throws Exception {
    residentOf(doomed, HouseholdRole.ADMIN);
    var start = new CyclicBarrier(2);
    var transfer =
        command(
            "transfer",
            FinalAccountDisposition.builder()
                .choice(FinalAccountChoice.TRANSFER)
                .destinationHouseholdId(refuge.getId())
                .build());
    var delete =
        command(
            "delete", FinalAccountDisposition.builder().choice(FinalAccountChoice.DELETE).build());

    List<Outcome<UUID, TeardownRejections.TearDown>> outcomes;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Callable<Outcome<UUID, TeardownRejections.TearDown>>> calls =
          List.of(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return service.tearDownHousehold(identity(), transfer);
              },
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return service.tearDownHousehold(identity(), delete);
              });
      outcomes = executor.invokeAll(calls).stream().map(this::completedOutcome).toList();
    }

    assertThat(outcomes).filteredOn(Outcome.Accepted.class::isInstance).hasSize(1);
  }

  private TearDownHouseholdCommand command(String reason, FinalAccountDisposition disposition) {
    return TearDownHouseholdCommand.builder()
        .householdId(doomed.getId())
        .reason(reason)
        .finalAccount(disposition)
        .build();
  }

  private UserAccount residentOf(Household household, HouseholdRole role) {
    var account =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(household.getId())
                .householdRole(role)
                .build());
    profiles.save(
        ProfileFixture.defaultProfileBuilder()
            .id(account.getPersonalProfileId())
            .householdId(household.getId())
            .name("Resident " + account.getId())
            .build());
    shares.share(account.getPersonalProfileId(), household.getId(), true);
    return account;
  }

  private HouseholdTeardownService serviceUsing(FakeUserAccountRepository accountRepository) {
    var registrationLifecycle = new DeviceRegistrationLifecycle(registrations, sessions);
    return new HouseholdTeardownService(
        authorization,
        new AccountRemoval(
            accountRepository,
            profiles,
            shares,
            managers,
            managerInvitations,
            accountInvitations,
            passwordResetCodes,
            sessions,
            registrationLifecycle),
        households,
        accountRepository,
        profiles,
        shares,
        sessions,
        registrationLifecycle,
        accountInvitations,
        audit,
        progress,
        new MutationTransactions(new FakeTransactionManager(), new ConstraintViolationTranslator()),
        Clock.systemUTC());
  }

  private Outcome<UUID, TeardownRejections.TearDown> completedOutcome(
      Future<Outcome<UUID, TeardownRejections.TearDown>> future) {
    try {
      return future.get();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while awaiting teardown", exception);
    } catch (ExecutionException exception) {
      throw new AssertionError("concurrent teardown failed", exception.getCause());
    }
  }

  private AuthenticatedIdentity identity() {
    return authorization.currentIdentity();
  }

  private static Object rejectionOf(Outcome<?, ?> outcome) {
    return switch (outcome) {
      case Outcome.Rejected<?, ?>(var rejections) -> rejections.getFirst();
      case Outcome.Accepted<?, ?> accepted ->
          throw new AssertionError("expected a rejection but got " + accepted);
    };
  }
}
