package com.streamarr.server.services.auth;

import static com.streamarr.server.support.PostgresLockTestSupport.lockAccountRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;

/**
 * A redemption that meets a held Account row must give up within the configured lock timeout
 * instead of holding a pool connection for as long as the other transaction runs.
 */
@Tag("IntegrationTest")
@DisplayName("Password Reset Lock Timeout Integration Tests")
class PasswordResetLockTimeoutIT extends AbstractIntegrationTest {

  @Autowired private PasswordResetService passwordResetService;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private OpaqueOneTimeCodes opaqueCodes;
  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("Should give up redemption within the lock timeout when the Account row is held")
  void shouldGiveUpRedemptionWithinLockTimeoutWhenAccountRowIsHeld() throws Exception {
    var account = authTestSupport.createAccount();
    var passwordHashBefore = account.getPasswordHash();
    var issued = opaqueCodes.issue();
    var code =
        resetCodeRepository.saveAndFlush(
            PasswordResetCode.builder()
                .accountId(account.getId())
                .issuerAccountId(account.getId())
                .expiresAt(Instant.now().plus(Duration.ofHours(1)))
                .publicId(issued.publicId())
                .secretDigest(issued.digest())
                .build());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var holder = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      lockAccountRow(holder, account.getId());

      var redemption =
          executor.submit(
              () -> {
                passwordResetService.redeem(
                    RedeemPasswordResetCommand.builder()
                        .code(issued.code())
                        .newPassword("a new passphrase")
                        .ipAddress("192.0.2.30")
                        .build());
                return null;
              });

      assertThatThrownBy(() -> redemption.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .cause()
          .isInstanceOf(PessimisticLockingFailureException.class);
      assertThat(passwordHashOf(account.getId())).isEqualTo(passwordHashBefore);
      assertThat(resetCodeRepository.findById(code.getId()).orElseThrow().getStatus())
          .isEqualTo(PasswordResetCodeStatus.PENDING);
    } finally {
      resetCodeRepository.deleteById(code.getId());
      authTestSupport.deleteAccount(account.getId());
    }
  }

  private String passwordHashOf(UUID accountId) {
    return userAccountRepository.findById(accountId).orElseThrow().getPasswordHash();
  }
}
