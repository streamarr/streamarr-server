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
@DisplayName("Portable Identity Service Integration Tests")
class PortableIdentityServiceIT extends AbstractIntegrationTest {

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
    var managementService =
        new SerializationProfileManagementService(
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
    var portableIdentityService =
        PortableIdentityService.builder()
            .transactionTemplate(
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)))
            .managementService(managementService)
            .build();

    try (var concurrentConnection = dataSource.getConnection();
        var threadExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      concurrentConnection.setAutoCommit(false);
      var concurrentUpdate =
          threadExecutor.submit(
              () -> updateConcurrently(concurrentConnection, householdId, signals));

      portableIdentityService.renamePortableProfile(
          RenamePortableProfileCommand.builder()
              .actingAccountId(UUID.randomUUID())
              .profileId(UUID.randomUUID())
              .name("Retry Profile")
              .build());

      assertThat(concurrentUpdate.get(30, TimeUnit.SECONDS)).isNull();
    }

    assertThat(attempts).hasValue(2);
    assertThat(safetyVersion(householdId)).isEqualTo(initialVersion + 2);
  }

  /**
   * Creates a uniquely named household with an associated owner account.
   *
   * @return the identifier of the created household
   */
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

  /**
   * Performs and commits a concurrent household safety-version update.
   *
   * @param connection  the database connection used for the update
   * @param householdId the household whose safety version is incremented
   * @param signals     synchronization signals for coordinating the concurrent transaction
   * @return the SQL exception if the update fails, or {@code null} if it succeeds
   */
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

  /**
   * Retrieves the current safety version for a household.
   *
   * @param householdId the household identifier
   * @return the household's current safety version
   */
  private long safetyVersion(UUID householdId) {
    return jdbcTemplate.queryForObject(
        "SELECT safety_version FROM household WHERE id = ?", Long.class, householdId);
  }

  /**
   * Rolls back the connection and attaches any rollback failure to the original SQL exception.
   *
   * @param connection the connection to roll back
   * @param failure the original SQL exception
   */
  private void rollback(Connection connection, SQLException failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  /**
   * Waits for a synchronization latch to complete within ten seconds.
   *
   * @param latch the latch to await
   * @throws IllegalStateException if the wait is interrupted or the latch does not complete in time
   */
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

  private static final class SerializationProfileManagementService
      extends ProfileManagementService {

    private final Runnable operation;

    /**
     * Creates a profile management service that executes the supplied operation.
     *
     * @param operation the operation to execute when the service handles a rename
     */
    private SerializationProfileManagementService(Runnable operation) {
      super(null, null, null, null, null, null, null, null);
      this.operation = operation;
    }

    /**
     * Executes the configured profile rename operation.
     *
     * @param command the portable profile rename command
     */
    @Override
    public void rename(RenamePortableProfileCommand command) {
      operation.run();
    }
  }
}
