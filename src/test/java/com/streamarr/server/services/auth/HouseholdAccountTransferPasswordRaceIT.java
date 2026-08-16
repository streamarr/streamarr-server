package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
@DisplayName("Household Account Transfer Password Race Integration Tests")
class HouseholdAccountTransferPasswordRaceIT extends AbstractIntegrationTest {

  @Autowired private HouseholdAdministrationService householdAdministrationService;
  @Autowired private PasswordChangeService passwordChangeService;
  @Autowired private RefreshTokenService refreshTokenService;
  @Autowired private LoginService loginService;
  @Autowired private UserAccountRepository accountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("Should preserve a successful password change when account transfer overlaps")
  void shouldPreserveSuccessfulPasswordChangeWhenAccountTransferOverlaps() throws Exception {
    var oldPassword = UUID.randomUUID().toString();
    var newPassword = UUID.randomUUID().toString();
    var administrator = authTestSupport.createAdminIdentity();
    var destinationOwner = authTestSupport.createAccount();
    var target =
        new TransactionTemplate(transactionManager)
            .execute(
                _ ->
                    accountRepository.save(
                        AccountFixture.defaultAccountBuilder()
                            .passwordHash(passwordEncoder.encode(oldPassword))
                            .homeHouseholdId(administrator.household().getId())
                            .householdRole(HouseholdRole.MEMBER)
                            .build()));
    var caller = refreshTokenService.createSession(target, "transfer-race-device").session();
    var preparedTransfer =
        householdAdministrationService.prepare(
            AccountHouseholdTransferCommand.builder()
                .actingAccountId(administrator.account().getId())
                .targetAccountId(target.getId())
                .targetHouseholdId(destinationOwner.getHomeHouseholdId())
                .targetRole(HouseholdRole.MEMBER)
                .password(authTestSupport.password())
                .reason("Concurrent password-change test")
                .build());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var blockerConnection = dataSource.getConnection()) {
      blockerConnection.setAutoCommit(false);
      lockAccount(blockerConnection, target.getId());

      var passwordChange =
          executor.submit(
              () ->
                  passwordChangeService.changePassword(
                      ChangePasswordCommand.builder()
                          .accountId(target.getId())
                          .sessionId(caller.getId())
                          .currentPassword(oldPassword)
                          .newPassword(newPassword)
                          .build()));
      awaitBlockedAccountOperations(1);

      var transfer =
          executor.submit(() -> householdAdministrationService.transferAccount(preparedTransfer));
      awaitBlockedAccountOperations(2);
      blockerConnection.rollback();

      passwordChange.get(30, TimeUnit.SECONDS);
      transfer.get(30, TimeUnit.SECONDS);
    }

    var targetEmail = target.getEmail();
    assertThatCode(() -> login(targetEmail, newPassword)).doesNotThrowAnyException();
    assertThatThrownBy(() -> login(targetEmail, oldPassword))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  private void lockAccount(Connection connection, UUID accountId) throws SQLException {
    try (var statement =
        connection.prepareStatement("SELECT id FROM user_account WHERE id = ? FOR UPDATE")) {
      statement.setObject(1, accountId);
      assertThat(statement.executeQuery().next()).isTrue();
    }
  }

  private void awaitBlockedAccountOperations(int expectedCount) {
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
                              AND query ILIKE '%user_account%'
                            """,
                            Integer.class))
                    .isGreaterThanOrEqualTo(expectedCount));
  }

  private LoginResult login(String email, String password) {
    return loginService.login(
        LoginCommand.builder()
            .email(email)
            .password(password)
            .deviceName("transfer-race-login")
            .source("transfer-race-test")
            .build());
  }
}
