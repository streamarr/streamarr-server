package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.TransferLastAccountAndDeleteHouseholdCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@Isolated("Uses the shared PostgreSQL identity fixtures")
@DisplayName("Household Deletion Concurrency Integration Tests")
class HouseholdDeletionConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private HouseholdDeletionService service;
  @Autowired private AuthTestSupport auth;
  @Autowired private HouseholdRepository households;
  @Autowired private UserAccountRepository accounts;
  @Autowired private ProfileRepository profiles;
  @Autowired private ProfileHouseholdShareRepository shares;
  @Autowired private AuthSessionRepository sessions;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DSLContext dsl;

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
            .extracting(profile -> profile.getHouseholdId())
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
}
