package com.streamarr.server.services.identity;

import static com.streamarr.server.fixtures.AccountInvitationFixture.pendingInvitationBuilder;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SourceHouseholdAccess;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AccountRemovalConflictException;
import com.streamarr.server.exceptions.ResourceBusyException;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.identity.AccountLifecycleService.AdministrativelyDeleteAccountCommand;
import com.streamarr.server.services.identity.AccountLifecycleService.ProfileCleanup;
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import com.streamarr.server.services.identity.ProfileLifecycleService.TransferProfileCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockProbe;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.TestFixtureWork;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Lifecycle Concurrency Integration Tests")
class LifecycleConcurrencyIT extends AbstractIntegrationTest {

  private static final String ACCOUNT_TRANSFER = "%update%user_account%household_id%";
  private static final String PROFILE_ROW_LOCK = "%from%profile%for update%";
  private static final String ISSUED_INVITATION_INVALIDATION =
      "%update%account_invitation%issuer_account_id%";
  private static final String SESSION_REVOCATION = "%update%auth_session%revoked_at%";
  private static final String REGISTRATION_REVOCATION = "%update%device_registration%revoked_at%";
  private static final String COMMIT = "commit";

  @Autowired private AccountLifecycleService accountLifecycleService;
  @Autowired private ProfileLifecycleService profileLifecycleService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private UserAccountRepository accountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private AuthSessionRepository sessionRepository;
  @Autowired private ProfileManagerRepository managerRepository;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private DeviceRegistrationRepository registrationRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("Should preserve a password change when an Account transfer commits after it")
  void shouldPreservePasswordChangeWhenAccountTransferCommitsAfterIt() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var destination = authTestSupport.createIdentity();
    var mover = residentOf(source.household().getId(), HouseholdRole.MEMBER);
    try {
      var transfer =
          commitCompetitorWhileOperationWaits(
              CompetitorHoldsRows.builder()
                  .competitorWrite(
                      () ->
                          assertThat(
                                  accountRepository.trySetPasswordHash(
                                      mover.getId(), "concurrent-password"))
                              .isTrue())
                  .operation(
                      () ->
                          accountLifecycleService.transferAccount(
                              authenticated(actor), transferTo(mover, destination)))
                  .blockedStatement(ACCOUNT_TRANSFER)
                  .build());

      assertThat(transfer).isInstanceOf(Outcome.Accepted.class);
      assertThat(accountRepository.findById(mover.getId()).orElseThrow().getPasswordHash())
          .isEqualTo("concurrent-password");
    } finally {
      authTestSupport.deleteIdentity(destination);
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName("Should preserve a rename when an Account transfer commits after it")
  void shouldPreserveRenameWhenAccountTransferCommitsAfterIt() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var destination = authTestSupport.createIdentity();
    var mover = residentOf(source.household().getId(), HouseholdRole.MEMBER);
    try {
      var transfer =
          commitCompetitorWhileOperationWaits(
              CompetitorHoldsRows.builder()
                  .competitorWrite(
                      () ->
                          assertThat(
                                  accountRepository.tryRename(mover.getId(), "Concurrent rename"))
                              .isTrue())
                  .operation(
                      () ->
                          accountLifecycleService.transferAccount(
                              authenticated(actor), transferTo(mover, destination)))
                  .blockedStatement(ACCOUNT_TRANSFER)
                  .build());

      assertThat(transfer).isInstanceOf(Outcome.Accepted.class);
      assertThat(accountRepository.findById(mover.getId()).orElseThrow().getDisplayName())
          .isEqualTo("Concurrent rename");
    } finally {
      authTestSupport.deleteIdentity(destination);
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName("Should reject deletion when a concurrent transfer changes the Account Household")
  void shouldRejectDeletionWhenConcurrentTransferChangesAccountHousehold() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var destination = authTestSupport.createIdentity();
    var mover = residentOf(source.household().getId(), HouseholdRole.MEMBER);
    managerRepository.saveAndFlush(
        ProfileManager.builder()
            .accountId(destination.account().getId())
            .profileId(mover.getPersonalProfileId())
            .build());
    var invitation =
        invitationRepository.saveAndFlush(
            pendingInvitationBuilder()
                .householdId(source.household().getId())
                .householdName(source.household().getName())
                .issuerAccountId(mover.getId())
                .build());
    try {
      var deletion =
          commitCompetitorWhileOperationWaitsOnHeldRow(
              HeldRowRace.builder()
                  .heldRow(rowOf("account_invitation", invitation.getId()))
                  .operation(
                      () ->
                          accountLifecycleService.administrativelyDeleteAccount(
                              authenticated(actor),
                              AdministrativelyDeleteAccountCommand.builder()
                                  .accountId(mover.getId())
                                  .profileCleanup(ProfileCleanup.PRESERVE_PROFILE)
                                  .replacementManagerAccountId(source.account().getId())
                                  .reason("departed")
                                  .build()))
                  .blockedStatement(ISSUED_INVITATION_INVALIDATION)
                  .competitor(
                      () ->
                          assertThat(
                                  accountLifecycleService.transferAccount(
                                      authenticated(actor), transferTo(mover, destination)))
                              .isInstanceOf(Outcome.Accepted.class))
                  .build());

      assertThat(deletion).isInstanceOf(Outcome.Rejected.class);
      assertThat(((Outcome.Rejected<?, ?>) deletion).rejections())
          .singleElement()
          .isInstanceOf(TransferRejections.AccountNotFound.class);
      assertThat(accountRepository.findById(mover.getId()).orElseThrow().getHouseholdId())
          .isEqualTo(destination.household().getId());
      assertThat(
              managerRepository.existsByAccountIdAndProfileId(
                  source.account().getId(), mover.getPersonalProfileId()))
          .isFalse();
      assertThat(
              managerRepository.existsByAccountIdAndProfileId(
                  destination.account().getId(), mover.getPersonalProfileId()))
          .isTrue();
    } finally {
      invitationRepository.deleteById(invitation.getId());
      authTestSupport.deleteIdentity(destination);
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName("Should allow exactly one winner when unlinked Profile transfers race")
  void shouldAllowExactlyOneWinnerWhenUnlinkedProfileTransfersRace() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var firstDestination = authTestSupport.createIdentity();
    var secondDestination = authTestSupport.createIdentity();
    var orphan = orphanOf(source);
    try {
      var attempts =
          releaseHeldRowOnceContendersQueue(
              QueuedRace.builder()
                  .heldRow(rowOf("profile", orphan.getId()))
                  .contenders(
                      List.of(
                          new Contender(
                              PROFILE_ROW_LOCK,
                              () ->
                                  profileLifecycleService.transferProfile(
                                      authenticated(actor),
                                      transferProfileBuilder(orphan)
                                          .destinationHouseholdId(
                                              firstDestination.household().getId())
                                          .localManagerAccountId(firstDestination.account().getId())
                                          .build())),
                          new Contender(
                              PROFILE_ROW_LOCK,
                              () ->
                                  profileLifecycleService.transferProfile(
                                      authenticated(actor),
                                      transferProfileBuilder(orphan)
                                          .destinationHouseholdId(
                                              secondDestination.household().getId())
                                          .localManagerAccountId(
                                              secondDestination.account().getId())
                                          .build()))))
                  .build());

      assertThat(attempts)
          .as("both contenders must return protocol-independent outcomes")
          .allMatch(result -> result instanceof Outcome<?, ?>);
      assertThat(attempts)
          .filteredOn(result -> result instanceof Outcome.Accepted<?, ?>)
          .as("the home transition must have a single winner")
          .hasSize(1);
      var rejected =
          (Outcome.Rejected<?, ?>)
              attempts.stream()
                  .filter(result -> result instanceof Outcome.Rejected<?, ?>)
                  .findFirst()
                  .orElseThrow();
      assertThat(rejected.rejections())
          .singleElement()
          .isInstanceOf(TransferRejections.ProfileNotFound.class);
      var accepted =
          (Outcome.Accepted<?, ?>)
              attempts.stream()
                  .filter(result -> result instanceof Outcome.Accepted<?, ?>)
                  .findFirst()
                  .orElseThrow();
      var returnedProfile = (Profile) accepted.result();
      var storedProfile = profileRepository.findById(orphan.getId()).orElseThrow();
      assertThat(returnedProfile.getHouseholdId()).isEqualTo(storedProfile.getHouseholdId());
      var winner =
          storedProfile.getHouseholdId().equals(firstDestination.household().getId())
              ? firstDestination
              : secondDestination;
      var loser = winner == firstDestination ? secondDestination : firstDestination;
      assertThat(
              managerRepository.existsByAccountIdAndProfileId(
                  winner.account().getId(), orphan.getId()))
          .isTrue();
      assertThat(
              managerRepository.existsByAccountIdAndProfileId(
                  loser.account().getId(), orphan.getId()))
          .isFalse();
      var activeShares =
          shareRepository.findByProfileIdAndStatus(orphan.getId(), ProfileShareStatus.ACTIVE);
      assertThat(activeShares).as("only the winning home share may remain active").hasSize(1);
      assertThat(activeShares.getFirst().getHouseholdId())
          .isEqualTo(storedProfile.getHouseholdId());
      assertThat(shareRepository.findByProfileIdAndStatus(orphan.getId(), ProfileShareStatus.ENDED))
          .singleElement()
          .extracting(ProfileHouseholdShare::getHouseholdId)
          .isEqualTo(source.household().getId());
    } finally {
      authTestSupport.deleteIdentity(firstDestination);
      authTestSupport.deleteIdentity(secondDestination);
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName("Should reject Profile transfer when Account linking commits after preflight")
  void shouldRejectProfileTransferWhenAccountLinkingCommitsAfterPreflight() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var destination = authTestSupport.createIdentity();
    var orphan = orphanOf(source);
    try {
      var transfer =
          commitCompetitorWhileOperationWaits(
              CompetitorHoldsRows.builder()
                  .competitorWrite(() -> linkProfile(orphan, source.household().getId()))
                  .operation(
                      () ->
                          profileLifecycleService.transferProfile(
                              authenticated(actor),
                              transferProfileBuilder(orphan)
                                  .destinationHouseholdId(destination.household().getId())
                                  .localManagerAccountId(destination.account().getId())
                                  .build()))
                  .blockedStatement(PROFILE_ROW_LOCK)
                  .build());

      assertThat(transfer).isInstanceOf(Outcome.Rejected.class);
      assertThat(((Outcome.Rejected<?, ?>) transfer).rejections())
          .singleElement()
          .isInstanceOf(TransferRejections.ProfileLinked.class);
      assertThat(profileRepository.findById(orphan.getId()).orElseThrow().getHouseholdId())
          .isEqualTo(source.household().getId());
      assertThat(
              managerRepository.existsByAccountIdAndProfileId(
                  destination.account().getId(), orphan.getId()))
          .isFalse();
    } finally {
      authTestSupport.deleteIdentity(destination);
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName(
      "Should return ProfileNotFound when administrative deletion commits after transfer preflight")
  void shouldReturnProfileNotFoundWhenAdministrativeDeletionCommitsAfterTransferPreflight()
      throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var destination = authTestSupport.createIdentity();
    var orphan = orphanOf(source);
    try {
      var attempts =
          releaseHeldRowOnceContendersQueue(
              QueuedRace.builder()
                  .heldRow(householdGuardOf(source.household().getId()))
                  .contenders(
                      List.of(
                          new Contender(
                              COMMIT,
                              () ->
                                  profileLifecycleService.administrativelyDeleteProfile(
                                      authenticated(actor), orphan.getId(), "cleanup")),
                          new Contender(
                              PROFILE_ROW_LOCK,
                              () ->
                                  profileLifecycleService.transferProfile(
                                      authenticated(actor),
                                      transferProfileBuilder(orphan)
                                          .destinationHouseholdId(destination.household().getId())
                                          .localManagerAccountId(destination.account().getId())
                                          .build()))))
                  .build());
      var deletion = attempts.getFirst();
      var transfer = attempts.getLast();

      assertThat(deletion).isInstanceOf(Outcome.Accepted.class);
      assertThat(transfer).isInstanceOf(Outcome.Rejected.class);
      assertThat(((Outcome.Rejected<?, ?>) transfer).rejections())
          .singleElement()
          .isInstanceOf(TransferRejections.ProfileNotFound.class);
      assertThat(profileRepository.findById(orphan.getId())).isEmpty();
      assertThat(
              managerRepository.existsByAccountIdAndProfileId(
                  destination.account().getId(), orphan.getId()))
          .isFalse();
      assertThat(
              shareRepository.findByProfileIdAndHouseholdIdAndStatus(
                  orphan.getId(), destination.household().getId(), ProfileShareStatus.ACTIVE))
          .isEmpty();
    } finally {
      authTestSupport.deleteIdentity(destination);
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName("Should return ProfileLinked when CONNECT wins an administrative deletion race")
  void shouldReturnProfileLinkedWhenConnectWinsAdministrativeDeletionRace() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var home = authTestSupport.createIdentity();
    var orphan = orphanOf(home);
    try {
      var deletion =
          commitCompetitorWhileOperationWaits(
              CompetitorHoldsRows.builder()
                  .competitorWrite(() -> linkProfile(orphan, home.household().getId()))
                  .operation(
                      () ->
                          profileLifecycleService.administrativelyDeleteProfile(
                              authenticated(actor), orphan.getId(), "cleanup"))
                  .blockedStatement(PROFILE_ROW_LOCK)
                  .build());

      assertThat(deletion).isInstanceOf(Outcome.Rejected.class);
      assertThat(((Outcome.Rejected<?, ?>) deletion).rejections())
          .singleElement()
          .isInstanceOf(TransferRejections.ProfileLinked.class);
      assertThat(profileRepository.findById(orphan.getId())).isPresent();
      assertThat(accountRepository.findByPersonalProfileId(orphan.getId())).isPresent();
      assertThat(
              shareRepository.findByProfileIdAndHouseholdIdAndStatus(
                  orphan.getId(), home.household().getId(), ProfileShareStatus.ACTIVE))
          .isPresent();
    } finally {
      authTestSupport.deleteIdentity(home);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName("Should return FinalAccount when concurrent deletions leave one resident")
  void shouldReturnFinalAccountWhenConcurrentDeletionsLeaveOneResident() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var household = authTestSupport.createIdentity();
    var second = residentOf(household.household().getId(), HouseholdRole.ADMIN);
    try {
      var attempts =
          releaseHeldRowOnceContendersQueue(
              QueuedRace.builder()
                  .heldRow(householdGuardOf(household.household().getId()))
                  .contenders(
                      List.of(
                          new Contender(
                              COMMIT,
                              () ->
                                  accountLifecycleService.administrativelyDeleteAccount(
                                      authenticated(actor), erase(household.account().getId()))),
                          new Contender(
                              COMMIT,
                              () ->
                                  accountLifecycleService.administrativelyDeleteAccount(
                                      authenticated(actor), erase(second.getId())))))
                  .build());

      assertThat(attempts)
          .as("both contenders must return protocol-independent outcomes")
          .allMatch(result -> result instanceof Outcome<?, ?>);
      assertThat(attempts)
          .filteredOn(result -> result instanceof Outcome.Accepted<?, ?>)
          .as("one winner was expected from %s", attempts)
          .hasSize(1);
      var rejected =
          (Outcome.Rejected<?, ?>)
              attempts.stream()
                  .filter(result -> result instanceof Outcome.Rejected<?, ?>)
                  .findFirst()
                  .orElseThrow();
      assertThat(rejected.rejections())
          .singleElement()
          .isInstanceOf(TransferRejections.FinalAccount.class);
      var deletedAccountId =
          (UUID)
              ((Outcome.Accepted<?, ?>)
                      attempts.stream()
                          .filter(result -> result instanceof Outcome.Accepted<?, ?>)
                          .findFirst()
                          .orElseThrow())
                  .result();
      var remainingAccountId =
          deletedAccountId.equals(household.account().getId())
              ? second.getId()
              : household.account().getId();
      assertThat(accountRepository.findById(deletedAccountId)).isEmpty();
      assertThat(accountRepository.findById(remainingAccountId)).isPresent();
      assertThat(accountRepository.findByHouseholdId(household.household().getId()))
          .singleElement()
          .extracting(UserAccount::getId)
          .isEqualTo(remainingAccountId);
    } finally {
      authTestSupport.deleteAccount(household.account().getId());
      authTestSupport.deleteAccount(second.getId());
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName("Should return FinalAccount when self-deletion loses a concurrent deletion race")
  void shouldReturnFinalAccountWhenSelfDeletionLosesConcurrentDeletionRace() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var household = authTestSupport.createIdentity();
    var other = residentOf(household.household().getId(), HouseholdRole.ADMIN);
    try {
      var selfDeletion =
          commitCompetitorWhileOperationWaitsOnHeldRow(
              HeldRowRace.builder()
                  .heldRow(rowOf("auth_session", household.session().getId()))
                  .operation(
                      () ->
                          accountLifecycleService.deleteMyAccount(
                              authenticated(household), "DELETE"))
                  .blockedStatement(SESSION_REVOCATION)
                  .competitor(
                      () ->
                          assertThat(
                                  accountLifecycleService.administrativelyDeleteAccount(
                                      authenticated(actor), erase(other.getId())))
                              .isInstanceOf(Outcome.Accepted.class))
                  .build());

      assertThat(selfDeletion).isInstanceOf(Outcome.Rejected.class);
      assertThat(((Outcome.Rejected<?, ?>) selfDeletion).rejections())
          .singleElement()
          .isInstanceOf(TransferRejections.FinalAccount.class);
      assertThat(accountRepository.findById(household.account().getId())).isPresent();
      assertThat(profileRepository.findById(household.account().getPersonalProfileId()))
          .isPresent();
      assertThat(
              sessionRepository.findById(household.session().getId()).orElseThrow().getRevokedAt())
          .as("self-deletion side effects must roll back with the rejected mutation")
          .isNull();
      assertThat(accountRepository.findById(other.getId())).isEmpty();
    } finally {
      authTestSupport.deleteAccount(other.getId());
      authTestSupport.deleteIdentity(household);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName(
      "Should report self-deletion busy and keep the Account when an administrator transfers it"
          + " meanwhile")
  void shouldReportSelfDeletionBusyAndKeepAccountWhenAdministratorTransfersItMeanwhile() {
    var actor = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var self = authTestSupport.createHouseholdMemberIdentity(source);
    var destination = authTestSupport.createIdentity();
    var registration = registrationAuthorizedBy(self);
    try {
      assertThatThrownBy(
              () ->
                  commitCompetitorWhileOperationWaitsOnHeldRow(
                      HeldRowRace.builder()
                          .heldRow(rowOf("device_registration", registration.getId()))
                          .operation(
                              () ->
                                  accountLifecycleService.deleteMyAccount(
                                      authenticated(self), "DELETE"))
                          .blockedStatement(REGISTRATION_REVOCATION)
                          .competitor(
                              () ->
                                  assertThat(
                                          accountLifecycleService.transferAccount(
                                              authenticated(actor),
                                              // END would also wait to revoke the held
                                              // registration.
                                              TransferAccountCommand.builder()
                                                  .accountId(self.account().getId())
                                                  .destinationHouseholdId(
                                                      destination.household().getId())
                                                  .sourceHouseholdAccess(
                                                      SourceHouseholdAccess.KEEP_AS_VISITOR)
                                                  .reason("relocated")
                                                  .build()))
                                      .isInstanceOf(Outcome.Accepted.class))
                          .build()))
          .isInstanceOf(ExecutionException.class)
          .cause()
          .isInstanceOf(ResourceBusyException.class)
          .hasCauseInstanceOf(AccountRemovalConflictException.class);

      assertThat(accountRepository.findById(self.account().getId()))
          .get()
          .extracting(UserAccount::getHouseholdId)
          .isEqualTo(destination.household().getId());
      assertThat(registrationRepository.findById(registration.getId()).orElseThrow().getRevokedAt())
          .as("self-deletion side effects must roll back with the busy mutation")
          .isNull();
    } finally {
      registrationRepository.deleteById(registration.getId());
      authTestSupport.deleteIdentity(destination);
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName(
      "Should return FinalAccount and keep one resident when all Accounts transfer concurrently")
  void shouldReturnFinalAccountAndKeepOneResidentWhenAllAccountsTransferConcurrently()
      throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var second = residentOf(source.household().getId(), HouseholdRole.ADMIN);
    var firstDestination = authTestSupport.createIdentity();
    var secondDestination = authTestSupport.createIdentity();
    try {
      var attempts =
          releaseHeldRowOnceContendersQueue(
              QueuedRace.builder()
                  .heldRow(householdGuardOf(source.household().getId()))
                  .contenders(
                      List.of(
                          new Contender(
                              COMMIT,
                              () ->
                                  accountLifecycleService.transferAccount(
                                      authenticated(actor),
                                      transferTo(source.account(), firstDestination))),
                          new Contender(
                              COMMIT,
                              () ->
                                  accountLifecycleService.transferAccount(
                                      authenticated(actor),
                                      transferTo(second, secondDestination)))))
                  .build());

      assertThat(attempts)
          .filteredOn(result -> result instanceof Outcome.Accepted<?, ?>)
          .singleElement();
      assertThat(attempts)
          .filteredOn(result -> result instanceof Outcome.Rejected<?, ?>)
          .singleElement()
          .satisfies(
              rejected ->
                  assertThat(((Outcome.Rejected<?, ?>) rejected).rejections())
                      .singleElement()
                      .isInstanceOf(TransferRejections.FinalAccount.class));
      var movedAccount =
          (UserAccount)
              ((Outcome.Accepted<?, ?>)
                      attempts.stream()
                          .filter(result -> result instanceof Outcome.Accepted<?, ?>)
                          .findFirst()
                          .orElseThrow())
                  .result();
      var remainingAccountId =
          movedAccount.getId().equals(source.account().getId())
              ? second.getId()
              : source.account().getId();
      assertThat(accountRepository.findByHouseholdId(source.household().getId()))
          .singleElement()
          .extracting(UserAccount::getId)
          .isEqualTo(remainingAccountId);
      var persistedMover = accountRepository.findById(movedAccount.getId()).orElseThrow();
      assertThat(persistedMover.getHouseholdId())
          .isIn(firstDestination.household().getId(), secondDestination.household().getId());
      assertThat(
              profileRepository
                  .findById(movedAccount.getPersonalProfileId())
                  .orElseThrow()
                  .getHouseholdId())
          .isEqualTo(persistedMover.getHouseholdId());
    } finally {
      authTestSupport.deleteIdentity(firstDestination);
      authTestSupport.deleteIdentity(secondDestination);
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteAccount(second.getId());
      authTestSupport.deleteIdentity(actor);
    }
  }

  @Test
  @DisplayName("Should allow exactly one winner when transfers of the same Account race")
  void shouldAllowExactlyOneWinnerWhenTransfersOfSameAccountRace() throws Exception {
    var actor = authTestSupport.createAdminIdentity();
    var source = authTestSupport.createIdentity();
    var mover = residentOf(source.household().getId(), HouseholdRole.MEMBER);
    var firstDestination = authTestSupport.createIdentity();
    var secondDestination = authTestSupport.createIdentity();
    try {
      var attempts =
          releaseHeldRowOnceContendersQueue(
              QueuedRace.builder()
                  .heldRow(rowOf("user_account", mover.getId()))
                  .contenders(
                      List.of(
                          new Contender(
                              ACCOUNT_TRANSFER,
                              () ->
                                  accountLifecycleService.transferAccount(
                                      authenticated(actor), transferTo(mover, firstDestination))),
                          new Contender(
                              ACCOUNT_TRANSFER,
                              () ->
                                  accountLifecycleService.transferAccount(
                                      authenticated(actor), transferTo(mover, secondDestination)))))
                  .build());

      var accepted =
          (Outcome.Accepted<?, ?>)
              attempts.stream()
                  .filter(result -> result instanceof Outcome.Accepted<?, ?>)
                  .findFirst()
                  .orElseThrow();
      assertThat(attempts)
          .filteredOn(result -> result instanceof Outcome.Accepted<?, ?>)
          .as("the Account home transition must have a single winner")
          .hasSize(1);
      assertThat(attempts)
          .filteredOn(result -> result instanceof Outcome.Rejected<?, ?>)
          .singleElement()
          .satisfies(
              rejected ->
                  assertThat(((Outcome.Rejected<?, ?>) rejected).rejections())
                      .singleElement()
                      .isInstanceOf(TransferRejections.AccountNotFound.class));

      var returnedAccount = (UserAccount) accepted.result();
      var storedAccount = accountRepository.findById(mover.getId()).orElseThrow();
      assertThat(returnedAccount.getHouseholdId()).isEqualTo(storedAccount.getHouseholdId());
      assertThat(storedAccount.getHouseholdId())
          .isIn(firstDestination.household().getId(), secondDestination.household().getId());
      assertThat(profileRepository.findById(mover.getPersonalProfileId()).orElseThrow())
          .extracting(Profile::getHouseholdId)
          .isEqualTo(storedAccount.getHouseholdId());
      assertThat(
              shareRepository.findByProfileIdAndStatus(
                  mover.getPersonalProfileId(), ProfileShareStatus.ACTIVE))
          .singleElement()
          .extracting(ProfileHouseholdShare::getHouseholdId)
          .isEqualTo(storedAccount.getHouseholdId());
    } finally {
      authTestSupport.deleteIdentity(firstDestination);
      authTestSupport.deleteIdentity(secondDestination);
      authTestSupport.deleteIdentity(source);
      authTestSupport.deleteIdentity(actor);
    }
  }

  // The competitor's write keeps its row locks until its transaction commits, so the operation
  // started inside that transaction makes every read ahead of those rows, then waits on them. The
  // observer watches for that wait: the competitor's own transaction keeps a stale activity view.
  private Outcome<?, ?> commitCompetitorWhileOperationWaits(CompetitorHoldsRows race)
      throws Exception {
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var observer = dataSource.getConnection()) {
      var operation =
          transactionTemplate.execute(
              _ -> {
                race.competitorWrite().run();
                var competitorPid =
                    new PostgresLockProbe(entityManager, jdbcTemplate).currentBackendPid();
                var pending = executor.submit(race.operation());
                await()
                    .atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                        () ->
                            assertThat(
                                    waitersBehind(observer, competitorPid, race.blockedStatement()))
                                .as("the operation waits for the competitor's write")
                                .isOne());
                return pending;
              });
      return operation.get(10, TimeUnit.SECONDS);
    }
  }

  // The operation waits on the held row partway through its write, past every check it makes
  // first; the competitor commits in that window.
  private Outcome<?, ?> commitCompetitorWhileOperationWaitsOnHeldRow(HeldRowRace race)
      throws Exception {
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var held = lockRow(race.heldRow())) {
      var operation = executor.submit(race.operation());
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(
                          waitersBehind(jdbcTemplate, held.backendPid(), race.blockedStatement()))
                      .as("the operation waits on the held row")
                      .isOne());

      race.competitor().run();
      held.release();
      return operation.get(10, TimeUnit.SECONDS);
    }
  }

  // Each contender queues behind the held row, directly or behind an earlier contender, before
  // the next one starts; releasing the row lets them finish in the order they queued.
  private List<Outcome<?, ?>> releaseHeldRowOnceContendersQueue(QueuedRace race) throws Exception {
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var held = lockRow(race.heldRow())) {
      var attempts = new ArrayList<Future<Outcome<?, ?>>>();
      for (var contender : race.contenders()) {
        var alreadyWaiting =
            waitersBehind(jdbcTemplate, held.backendPid(), contender.blockedStatement());
        attempts.add(executor.submit(contender.operation()));
        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(
                () ->
                    assertThat(
                            waitersBehind(
                                jdbcTemplate, held.backendPid(), contender.blockedStatement()))
                        .as("contender %d waits behind the held row", attempts.size())
                        .isEqualTo(alreadyWaiting + 1));
      }

      held.release();
      var outcomes = new ArrayList<Outcome<?, ?>>();
      for (var attempt : attempts) {
        outcomes.add(attempt.get(10, TimeUnit.SECONDS));
      }

      return outcomes;
    }
  }

  // Account, Profile, manager and share writes lock their Households' guards in deferred checks at
  // commit, so holding a guard parks such a write at its COMMIT, after every statement it runs.
  private RowLockTarget householdGuardOf(UUID householdId) {
    return RowLockTarget.builder()
        .dataSource(dataSource)
        .table("household_guard")
        .keyColumn("household_id")
        .rowId(householdId)
        .build();
  }

  private RowLockTarget rowOf(String table, UUID rowId) {
    return RowLockTarget.builder().dataSource(dataSource).table(table).rowId(rowId).build();
  }

  private DeviceRegistration registrationAuthorizedBy(AuthTestSupport.TestIdentity identity) {
    return registrationRepository.saveAndFlush(
        DeviceRegistration.builder()
            .esn(UUID.randomUUID().toString())
            .displayName("Living room TV")
            .householdId(identity.household().getId())
            .authorizingAccountId(identity.account().getId())
            .build());
  }

  private UserAccount residentOf(UUID householdId, HouseholdRole role) {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder().householdId(householdId).build());
          var account =
              accountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(householdId)
                      .householdRole(role)
                      .personalProfileId(profile.getId())
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(householdId)
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }

  private static TransferAccountCommand transferTo(
      UserAccount mover, AuthTestSupport.TestIdentity destination) {
    return TransferAccountCommand.builder()
        .accountId(mover.getId())
        .destinationHouseholdId(destination.household().getId())
        .sourceHouseholdAccess(SourceHouseholdAccess.END)
        .reason("relocated")
        .build();
  }

  private Profile orphanOf(AuthTestSupport.TestIdentity manager) {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(manager.household().getId())
                      .build());
          managerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(manager.account().getId())
                  .profileId(profile.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(manager.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return profile;
        });
  }

  // The new Account's foreign key holds a key-share lock on the Profile row until commit.
  private void linkProfile(Profile profile, UUID householdId) {
    var homeShare =
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                profile.getId(), householdId, ProfileShareStatus.ACTIVE)
            .orElseThrow();
    homeShare.setStructural(true);
    shareRepository.saveAndFlush(homeShare);
    accountRepository.saveAndFlush(
        AccountFixture.defaultAccountBuilder()
            .householdId(householdId)
            .householdRole(HouseholdRole.MEMBER)
            .personalProfileId(profile.getId())
            .build());
  }

  private static TransferProfileCommand.TransferProfileCommandBuilder transferProfileBuilder(
      Profile profile) {
    return TransferProfileCommand.builder().profileId(profile.getId()).reason("recovery");
  }

  private static AdministrativelyDeleteAccountCommand erase(UUID accountId) {
    return AdministrativelyDeleteAccountCommand.builder()
        .accountId(accountId)
        .profileCleanup(ProfileCleanup.ERASE_PROFILE)
        .reason("departed")
        .build();
  }

  private static AuthenticatedIdentity authenticated(AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.builder()
        .accountId(identity.account().getId())
        .authSessionId(identity.session().getId())
        .scope(TokenScope.ACCOUNT)
        .householdId(identity.household().getId())
        .householdRole(identity.account().getHouseholdRole())
        .contextHouseholdId(identity.household().getId())
        .reauthenticatedAt(Optional.of(Instant.now().minusSeconds(1)))
        .build();
  }

  /**
   * A competitor write that holds row locks in the test's transaction, and an operation that waits
   * on them at {@code blockedStatement} (ILIKE).
   */
  @Builder
  private record CompetitorHoldsRows(
      Runnable competitorWrite, Callable<Outcome<?, ?>> operation, String blockedStatement) {}

  /**
   * An operation that waits on {@code heldRow} at {@code blockedStatement} (ILIKE), and a
   * competitor that commits meanwhile.
   */
  @Builder
  private record HeldRowRace(
      RowLockTarget heldRow,
      Callable<Outcome<?, ?>> operation,
      String blockedStatement,
      Runnable competitor) {}

  @Builder
  private record QueuedRace(RowLockTarget heldRow, List<Contender> contenders) {}

  /** An operation that queues behind the held row at {@code blockedStatement} (ILIKE). */
  private record Contender(String blockedStatement, Callable<Outcome<?, ?>> operation) {}
}
