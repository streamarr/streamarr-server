package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Portable Identity Transaction Executor Integration Tests")
class PortableIdentityTransactionExecutorIT extends AbstractIntegrationTest {

  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private UserAccountRepository accountRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("Should retry after real PostgreSQL serialization failure")
  void shouldRetryAfterRealPostgresSerializationFailure() throws Exception {
    var householdId = createHousehold();
    var initialVersion = safetyVersion(householdId);
    var firstReadCompleted = new CountDownLatch(1);
    var concurrentCommitCompleted = new CountDownLatch(1);
    var signals = new RaceSignals(firstReadCompleted, concurrentCommitCompleted);
    var attempts = new AtomicInteger();
    var executor =
        new PortableIdentityTransactionExecutor(new DataSourceTransactionManager(dataSource));

    try (var concurrentConnection = dataSource.getConnection();
        var threadExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      concurrentConnection.setAutoCommit(false);
      var concurrentUpdate =
          threadExecutor.submit(
              () -> updateConcurrently(concurrentConnection, householdId, signals));

      executor.execute(
          () -> {
            jdbcTemplate.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE");
            var observedVersion = safetyVersion(householdId);
            if (attempts.incrementAndGet() == 1) {
              firstReadCompleted.countDown();
              awaitLatch(concurrentCommitCompleted);
            }
            jdbcTemplate.update(
                "UPDATE household SET safety_version = ? WHERE id = ?",
                observedVersion + 1,
                householdId);
          });

      assertThat(concurrentUpdate.get(30, TimeUnit.SECONDS)).isNull();
    }

    assertThat(attempts).hasValue(2);
    assertThat(safetyVersion(householdId)).isEqualTo(initialVersion + 2);
  }

  private UUID createHousehold() {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var household =
                  householdRepository.save(
                      Household.builder().name("Retry Home " + UUID.randomUUID()).build());
              accountRepository.save(
                  UserAccount.builder()
                      .email("retry-owner-" + UUID.randomUUID() + "@example.com")
                      .displayName("Retry Owner")
                      .passwordHash("encoded")
                      .accountRole(AccountRole.USER)
                      .homeHouseholdId(household.getId())
                      .householdRole(HouseholdRole.OWNER)
                      .build());
              return household.getId();
            });
  }

  private Throwable updateConcurrently(
      Connection connection, UUID householdId, RaceSignals signals) {
    awaitLatch(signals.firstReadCompleted());
    try (var statement =
        connection.prepareStatement(
            "UPDATE household SET safety_version = safety_version + 1 WHERE id = ?")) {
      statement.setObject(1, householdId);
      statement.executeUpdate();
      connection.commit();
      signals.concurrentCommitCompleted().countDown();
      return null;
    } catch (SQLException exception) {
      signals.concurrentCommitCompleted().countDown();
      rollback(connection, exception);
      return exception;
    }
  }

  private long safetyVersion(UUID householdId) {
    return jdbcTemplate.queryForObject(
        "SELECT safety_version FROM household WHERE id = ?", Long.class, householdId);
  }

  private void rollback(Connection connection, SQLException failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  private void awaitLatch(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Serialization race was interrupted", exception);
    }
  }

  private record RaceSignals(
      CountDownLatch firstReadCompleted, CountDownLatch concurrentCommitCompleted) {}
}
