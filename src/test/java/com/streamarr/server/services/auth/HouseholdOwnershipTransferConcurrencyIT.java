package com.streamarr.server.services.auth;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Household Ownership Transfer Concurrency Integration Tests")
class HouseholdOwnershipTransferConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private HouseholdAdministrationService householdAdministrationService;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private UserAccountRepository accountRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("Should preserve exactly one owner when ownership transfers race")
  void shouldPreserveExactlyOneOwnerWhenOwnershipTransfersRace() throws Exception {
    var fixture = createFixture();
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var blockerConnection = dataSource.getConnection()) {
      blockerConnection.setAutoCommit(false);
      lockAccount(blockerConnection, fixture.currentOwnerId());

      var first =
          executor.submit(() -> transferOwnershipAfter(start, fixture, fixture.firstCandidateId()));
      var second =
          executor.submit(
              () -> transferOwnershipAfter(start, fixture, fixture.secondCandidateId()));
      start.countDown();

      awaitBothTransfersBlocked();
      blockerConnection.rollback();

      var failures =
          Stream.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))
              .filter(Objects::nonNull)
              .toList();
      assertThat(failures).hasSize(1);
      assertThat(ownerCount(fixture.householdId())).isOne();
    }
  }

  private Fixture createFixture() {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var household =
                  householdRepository.save(
                      Household.builder().name("Ownership Race " + UUID.randomUUID()).build());
              var currentOwner =
                  accountRepository.save(
                      account(household.getId(), HouseholdRole.OWNER, "ownership-password"));
              var firstCandidate =
                  accountRepository.save(
                      account(household.getId(), HouseholdRole.PARENT, "unused-password"));
              var secondCandidate =
                  accountRepository.save(
                      account(household.getId(), HouseholdRole.PARENT, "unused-password"));
              return new Fixture(
                  household.getId(),
                  currentOwner.getId(),
                  firstCandidate.getId(),
                  secondCandidate.getId());
            });
  }

  private UserAccount account(UUID householdId, HouseholdRole role, String password) {
    return UserAccount.builder()
        .email("ownership-race-" + UUID.randomUUID() + "@example.com")
        .displayName("Ownership Race Account")
        .passwordHash(passwordEncoder.encode(password))
        .accountRole(AccountRole.USER)
        .homeHouseholdId(householdId)
        .householdRole(role)
        .build();
  }

  private Throwable transferOwnershipAfter(
      CountDownLatch start, Fixture fixture, UUID targetAccountId) {
    awaitStart(start);
    try {
      var preparedTransfer =
          householdAdministrationService.prepare(
              HouseholdOwnershipTransferCommand.builder()
                  .authority(
                      accountIdentityBuilder()
                          .accountId(fixture.currentOwnerId())
                          .householdId(fixture.householdId())
                          .householdRole(HouseholdRole.OWNER)
                          .build())
                  .householdId(fixture.householdId())
                  .targetAccountId(targetAccountId)
                  .password("ownership-password")
                  .reason("Concurrent handoff")
                  .build());
      householdAdministrationService.transferOwnership(preparedTransfer);
      return null;
    } catch (RuntimeException exception) {
      return exception;
    }
  }

  private void lockAccount(Connection connection, UUID accountId) throws SQLException {
    try (var statement =
        connection.prepareStatement("SELECT id FROM user_account WHERE id = ? FOR UPDATE")) {
      statement.setObject(1, accountId);
      statement.executeQuery();
    }
  }

  private void awaitBothTransfersBlocked() {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        jdbcTemplate.queryForObject(
                            """
                            SELECT COUNT(*)
                            FROM pg_stat_activity
                            WHERE datname = current_database()
                              AND wait_event_type = 'Lock'
                              AND query ILIKE 'select%user_account%for update%'
                            """,
                            Integer.class))
                    .isGreaterThanOrEqualTo(2));
  }

  private int ownerCount(UUID householdId) {
    return jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*)
        FROM user_account
        WHERE home_household_id = ?
          AND household_role = 'OWNER'
        """,
        Integer.class,
        householdId);
  }

  private void awaitStart(CountDownLatch start) {
    try {
      assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Ownership transfer race was interrupted", exception);
    }
  }

  private record Fixture(
      UUID householdId, UUID currentOwnerId, UUID firstCandidateId, UUID secondCandidateId) {}
}
