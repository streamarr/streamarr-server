package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.Household.HOUSEHOLD;
import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SourceHouseholdAccess;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.identity.AccountLifecycleService.AdministrativelyDeleteAccountCommand;
import com.streamarr.server.services.identity.AccountLifecycleService.ProfileCleanup;
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteEmptyHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.TransferLastAccountAndDeleteHouseholdCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@Isolated("Uses the shared PostgreSQL identity fixtures")
@DisplayName("Household Deletion Concurrency Integration Tests")
class HouseholdDeletionConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private HouseholdDeletionService service;
  @Autowired private AccountLifecycleService accountLifecycle;
  @Autowired private ProfileAdministrationService profileAdministration;
  @Autowired private AdministrationQueryService administration;
  @Autowired private AuthTestSupport auth;
  @Autowired private HouseholdRepository households;
  @Autowired private UserAccountRepository accounts;
  @Autowired private ProfileRepository profiles;
  @Autowired private ProfileHouseholdShareRepository shares;
  @Autowired private AuthSessionRepository sessions;
  @Autowired private ProfileManagerRepository managers;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DSLContext dsl;

  @Test
  @DisplayName(
      "Should preserve both residents when another Account joins while deletion waits for its destination")
  void shouldPreserveBothResidentsWhenAnotherAccountJoinsWhileDeletionWaitsForDestination()
      throws Exception {
    var admin = auth.createAdminIdentity();
    var source = auth.createIdentity();
    var joining = auth.createIdentity();
    var destinationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    dsl.insertInto(HOUSEHOLD)
        .set(HOUSEHOLD.ID, destinationId)
        .set(HOUSEHOLD.NAME, "Empty destination")
        .execute();
    var identity = auth.freshIdentityOf(admin);
    assertThat(
            service.transferLastAccountAndDeleteHousehold(
                identity,
                TransferLastAccountAndDeleteHouseholdCommand.builder()
                    .householdId(joining.household().getId())
                    .destinationHouseholdId(admin.household().getId())
                    .reason("join donor Household")
                    .build()))
        .isEqualTo(Outcome.accepted(joining.household().getId()));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var held =
            PostgresLockTestSupport.lockRow(
                RowLockTarget.builder()
                    .dataSource(dataSource)
                    .table("household")
                    .rowId(destinationId)
                    .build())) {
      var deletion =
          executor.submit(
              () ->
                  service.transferLastAccountAndDeleteHousehold(
                      identity,
                      TransferLastAccountAndDeleteHouseholdCommand.builder()
                          .householdId(source.household().getId())
                          .destinationHouseholdId(destinationId)
                          .reason("close source")
                          .build()));
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () ->
                  assertThat(
                          PostgresLockTestSupport.waitersBehind(
                              jdbc, held.backendPid(), "%household%for update%"))
                      .isEqualTo(1));

      assertThat(
              accountLifecycle.transferAccount(
                  identity,
                  TransferAccountCommand.builder()
                      .accountId(joining.account().getId())
                      .destinationHouseholdId(source.household().getId())
                      .sourceHouseholdAccess(SourceHouseholdAccess.END)
                      .reason("join source")
                      .build()))
          .isInstanceOf(Outcome.Accepted.class);
      held.release();

      assertThat(deletion.get(5, TimeUnit.SECONDS))
          .isEqualTo(Outcome.rejected(new HouseholdDeletionRejections.AccountsRemain()));
      assertThat(accounts.findByHouseholdId(source.household().getId()))
          .extracting(BaseAuditableEntity::getId)
          .containsExactlyInAnyOrder(source.account().getId(), joining.account().getId());
      assertThat(profiles.findByHouseholdId(source.household().getId()))
          .extracting(BaseAuditableEntity::getId)
          .containsExactlyInAnyOrder(source.profile().getId(), joining.profile().getId());
      assertThat(
              shares.findByHouseholdIdAndStatus(
                  source.household().getId(), ProfileShareStatus.ACTIVE))
          .hasSize(2)
          .allSatisfy(share -> assertThat(share.isStructural()).isTrue());
      assertThat(sessions.findById(source.session().getId()))
          .get()
          .satisfies(
              session -> {
                assertThat(session.getRevokedAt()).isNull();
                assertThat(session.getContextHouseholdId()).isEqualTo(source.household().getId());
              });
      assertThat(households.findById(destinationId)).isPresent();
      assertThat(accounts.findByHouseholdId(destinationId)).isEmpty();
      assertThat(
              dsl.select(SECURITY_AUDIT_EVENT.REASON)
                  .from(SECURITY_AUDIT_EVENT)
                  .where(SECURITY_AUDIT_EVENT.ACTOR_ACCOUNT_ID.eq(admin.account().getId()))
                  .fetch(SECURITY_AUDIT_EVENT.REASON))
          .containsExactlyInAnyOrder("join donor Household", "join source");
    } finally {
      auth.deleteIdentity(joining);
      auth.deleteIdentity(source);
      auth.deleteIdentity(admin);
      households.deleteById(destinationId);
    }
  }

  @Test
  @DisplayName(
      "Should commit one complete disposition when deletion and transfer race for the same source")
  void shouldCommitOneCompleteDispositionWhenDeletionAndTransferRaceForSameSource()
      throws Exception {
    var admin = auth.createAdminIdentity();
    var source = auth.createIdentity();
    var identity = auth.freshIdentityOf(admin);
    var sourceId = source.household().getId();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var held =
            PostgresLockTestSupport.lockRow(
                RowLockTarget.builder()
                    .dataSource(dataSource)
                    .table("household")
                    .rowId(sourceId)
                    .build())) {
      var transfer =
          executor.submit(
              () ->
                  service.transferLastAccountAndDeleteHousehold(
                      identity,
                      TransferLastAccountAndDeleteHouseholdCommand.builder()
                          .householdId(sourceId)
                          .destinationHouseholdId(admin.household().getId())
                          .reason("transfer contender")
                          .build()));
      var deletion =
          executor.submit(
              () ->
                  service.deleteLastAccountAndHousehold(
                      identity,
                      DeleteLastAccountAndHouseholdCommand.builder()
                          .householdId(sourceId)
                          .reason("delete contender")
                          .build()));
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () ->
                  assertThat(
                          PostgresLockTestSupport.waitersBehind(
                              jdbc, held.backendPid(), "%household%for update%"))
                      .isEqualTo(2));
      held.release();
      var transferOutcome = transfer.get(5, TimeUnit.SECONDS);
      var deleteOutcome = deletion.get(5, TimeUnit.SECONDS);
      assertThat(List.of(transferOutcome, deleteOutcome))
          .containsExactlyInAnyOrder(
              Outcome.accepted(sourceId),
              Outcome.rejected(new HouseholdDeletionRejections.HouseholdNotFound()));
      assertThat(households.findById(sourceId)).isEmpty();
      if (transferOutcome instanceof Outcome.Accepted<?, ?>) {
        assertThat(sessions.findById(source.session().getId()))
            .get()
            .satisfies(
                session -> {
                  assertThat(session.getRevokedAt()).isNull();
                  assertThat(session.getContextHouseholdId()).isNull();
                  assertThat(session.getSelectedProfileId()).isNull();
                });
        assertThat(accounts.findById(source.account().getId()))
            .get()
            .satisfies(
                account -> {
                  assertThat(account.getHouseholdId()).isEqualTo(admin.household().getId());
                  assertThat(account.getHouseholdRole()).isEqualTo(HouseholdRole.MEMBER);
                });
        assertThat(profiles.findById(source.profile().getId()))
            .get()
            .extracting(Profile::getHouseholdId)
            .isEqualTo(admin.household().getId());
        assertThat(
                shares.findByProfileIdAndHouseholdIdAndStatus(
                    source.profile().getId(), admin.household().getId(), ProfileShareStatus.ACTIVE))
            .get()
            .extracting(share -> share.isStructural())
            .isEqualTo(true);
      } else {
        assertThat(accounts.findById(source.account().getId())).isEmpty();
        assertThat(profiles.findById(source.profile().getId())).isEmpty();
        assertThat(sessions.findById(source.session().getId())).isEmpty();
      }

      var operation =
          transferOutcome instanceof Outcome.Accepted<?, ?>
              ? "transferLastAccountAndDeleteHousehold"
              : "deleteLastAccountAndHousehold";
      assertThat(
              dsl.select(SECURITY_AUDIT_EVENT.OPERATION)
                  .from(SECURITY_AUDIT_EVENT)
                  .where(SECURITY_AUDIT_EVENT.ACTOR_ACCOUNT_ID.eq(admin.account().getId()))
                  .fetch(SECURITY_AUDIT_EVENT.OPERATION))
          .containsExactly(operation);
    } finally {
      auth.deleteIdentity(source);
      auth.deleteIdentity(admin);
    }
  }

  @Test
  @DisplayName(
      "Should preserve the source when its replacement manager becomes restricted during the lock wait")
  void shouldPreserveSourceWhenReplacementManagerBecomesRestrictedDuringLockWait()
      throws Exception {
    assertPreservationRejectedAfter(ReplacementChange.RESTRICTED);
  }

  @ParameterizedTest
  @EnumSource(
      value = ReplacementChange.class,
      names = {"MOVED", "REMOVED"})
  @DisplayName(
      "Should preserve the source when its replacement manager leaves the destination during the lock wait")
  void shouldPreserveSourceWhenReplacementManagerLeavesDestinationDuringLockWait(
      ReplacementChange change) throws Exception {
    assertPreservationRejectedAfter(change);
  }

  private void assertPreservationRejectedAfter(ReplacementChange change) throws Exception {
    var first = auth.createAdminIdentity();
    var second = auth.createAdminIdentity();
    // PostgreSQL orders UUID bytes unsigned. Park before deletion can lock the manager's Household.
    var firstHouseholdSortsFirst =
        first.household().getId().toString().compareTo(second.household().getId().toString()) < 0;
    var source = firstHouseholdSortsFirst ? first : second;
    var admin = firstHouseholdSortsFirst ? second : first;
    var replacement = auth.createIdentity();
    var elsewhere = auth.createIdentity();
    var identity = auth.freshIdentityOf(admin);
    assertThat(
            service.transferLastAccountAndDeleteHousehold(
                identity,
                TransferLastAccountAndDeleteHouseholdCommand.builder()
                    .householdId(replacement.household().getId())
                    .destinationHouseholdId(admin.household().getId())
                    .reason("join destination")
                    .build()))
        .isEqualTo(Outcome.accepted(replacement.household().getId()));
    managers.saveAndFlush(
        ProfileManager.builder()
            .accountId(admin.account().getId())
            .profileId(replacement.profile().getId())
            .build());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var held =
            PostgresLockTestSupport.lockRow(
                RowLockTarget.builder()
                    .dataSource(dataSource)
                    .table("household")
                    .rowId(source.household().getId())
                    .build())) {
      var deletion =
          executor.submit(
              () ->
                  service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
                      identity,
                      DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand.builder()
                          .householdId(source.household().getId())
                          .destinationHouseholdId(admin.household().getId())
                          .replacementManagerAccountId(replacement.account().getId())
                          .reason("preserve Profile")
                          .build()));
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () ->
                  assertThat(
                          PostgresLockTestSupport.waitersBehind(
                              jdbc, held.backendPid(), "%household%for update%"))
                      .isEqualTo(1));

      var competingOutcome =
          executor.submit(
              () ->
                  switch (change) {
                    case RESTRICTED ->
                        profileAdministration.setProfileContentCeiling(
                            identity, replacement.profile().getId(), 12);
                    case MOVED ->
                        accountLifecycle.transferAccount(
                            identity,
                            TransferAccountCommand.builder()
                                .accountId(replacement.account().getId())
                                .destinationHouseholdId(elsewhere.household().getId())
                                .sourceHouseholdAccess(SourceHouseholdAccess.END)
                                .reason("move replacement")
                                .build());
                    case REMOVED ->
                        accountLifecycle.administrativelyDeleteAccount(
                            identity,
                            AdministrativelyDeleteAccountCommand.builder()
                                .accountId(replacement.account().getId())
                                .profileCleanup(ProfileCleanup.ERASE_PROFILE)
                                .reason("remove replacement")
                                .build());
                  });
      assertThat(competingOutcome.get(5, TimeUnit.SECONDS)).isInstanceOf(Outcome.Accepted.class);
      held.release();

      assertThat(deletion.get(5, TimeUnit.SECONDS))
          .isEqualTo(
              Outcome.rejected(
                  change == ReplacementChange.REMOVED
                      ? new HouseholdDeletionRejections.ReplacementManagerNotFound()
                      : new HouseholdDeletionRejections.ReplacementManagerNotEligible()));
      assertThat(administration.accountAdministration(identity, source.account().getId()))
          .get()
          .extracting(UserAccount::getHouseholdId)
          .isEqualTo(source.household().getId());
      assertThat(administration.profileAdministration(identity, source.profile().getId()))
          .get()
          .extracting(details -> details.profile().getHouseholdId())
          .isEqualTo(source.household().getId());
      assertThat(
              shares.findByProfileIdAndHouseholdIdAndStatus(
                  source.profile().getId(), source.household().getId(), ProfileShareStatus.ACTIVE))
          .get()
          .extracting(share -> share.isStructural())
          .isEqualTo(true);
      assertThat(sessions.findById(source.session().getId()))
          .get()
          .extracting(AuthSession::getRevokedAt)
          .isNull();
      assertThat(
              managers.existsByAccountIdAndProfileId(
                  replacement.account().getId(), source.profile().getId()))
          .isFalse();
      assertThat(
              dsl.fetchCount(
                  SECURITY_AUDIT_EVENT,
                  SECURITY_AUDIT_EVENT
                      .ACTOR_ACCOUNT_ID
                      .eq(admin.account().getId())
                      .and(
                          SECURITY_AUDIT_EVENT.OPERATION.eq(
                              "deleteLastAccountAndHouseholdPreservingPersonalProfile"))))
          .isZero();
    } finally {
      auth.deleteIdentity(source);
      auth.deleteIdentity(replacement);
      auth.deleteIdentity(elsewhere);
      auth.deleteIdentity(admin);
    }
  }

  @Test
  @DisplayName("Should preserve the source when its destination is deleted during the lock wait")
  void shouldPreserveSourceWhenDestinationIsDeletedDuringLockWait() throws Exception {
    var admin = auth.createAdminIdentity();
    var source = auth.createIdentity();
    var destinationId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    dsl.insertInto(HOUSEHOLD)
        .set(HOUSEHOLD.ID, destinationId)
        .set(HOUSEHOLD.NAME, "Destination")
        .execute();
    var identity = auth.freshIdentityOf(admin);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var held =
            PostgresLockTestSupport.lockRow(
                RowLockTarget.builder()
                    .dataSource(dataSource)
                    .table("household")
                    .rowId(source.household().getId())
                    .build())) {
      var transfer =
          executor.submit(
              () ->
                  service.transferLastAccountAndDeleteHousehold(
                      identity,
                      TransferLastAccountAndDeleteHouseholdCommand.builder()
                          .householdId(source.household().getId())
                          .destinationHouseholdId(destinationId)
                          .reason("closing source")
                          .build()));
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () ->
                  assertThat(
                          PostgresLockTestSupport.waitersBehind(
                              jdbc, held.backendPid(), "%household%for update%"))
                      .isEqualTo(1));

      assertThat(
              service.deleteEmptyHousehold(
                  identity,
                  DeleteEmptyHouseholdCommand.builder()
                      .householdId(destinationId)
                      .reason("closing destination")
                      .build()))
          .isEqualTo(Outcome.accepted(destinationId));
      held.release();

      assertThat(transfer.get(5, TimeUnit.SECONDS))
          .isEqualTo(Outcome.rejected(new HouseholdDeletionRejections.DestinationNotFound()));
      assertThat(administration.accountAdministration(identity, source.account().getId()))
          .get()
          .extracting(UserAccount::getHouseholdId)
          .isEqualTo(source.household().getId());
      assertThat(administration.profileAdministration(identity, source.profile().getId()))
          .get()
          .extracting(details -> details.profile().getHouseholdId())
          .isEqualTo(source.household().getId());
      assertThat(service.deletionPreflight(identity, source.household().getId())).isPresent();
    } finally {
      auth.deleteIdentity(source);
      auth.deleteIdentity(admin);
      if (households.existsById(destinationId)) {
        households.deleteById(destinationId);
      }
    }
  }

  private enum ReplacementChange {
    RESTRICTED,
    MOVED,
    REMOVED
  }
}
