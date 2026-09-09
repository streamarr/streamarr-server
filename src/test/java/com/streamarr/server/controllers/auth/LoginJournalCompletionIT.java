package com.streamarr.server.controllers.auth;

import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.CredentialAttemptUnavailableException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.services.auth.LoginCommand;
import com.streamarr.server.services.auth.LoginService;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.HeldRowLock;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("IntegrationTest")
@DisplayName("Login Journal Completion Integration Tests")
@Import(LoginJournalCompletionIT.PasswordEncoderConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginJournalCompletionIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LoginService loginService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private AuthSessionRepository sessionRepository;
  @Autowired private CompletionBlockingPasswordEncoder passwordEncoder;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UserAccount account;
  private HeldRowLock journalLock;

  @BeforeEach
  void blockJournalCompletionAfterPasswordVerification() {
    account = authTestSupport.createAccount();
    passwordEncoder.afterNextMatch(this::lockReservedAttempt);
  }

  @AfterEach
  void releaseLockAndDeleteAccount() throws SQLException {
    passwordEncoder.clear();
    if (journalLock != null) {
      journalLock.close();
    }

    if (account != null) {
      jdbcTemplate.update("DELETE FROM credential_attempt WHERE account_id = ?", account.getId());
      authTestSupport.deleteAccount(account.getId());
    }
  }

  @Test
  @DisplayName(
      "Should return unavailable without creating a session when journal completion times out")
  void shouldReturnUnavailableWithoutCreatingSessionWhenJournalCompletionTimesOut()
      throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email": "%s", "password": "%s", "deviceName": "journal-outage", "cookieMode": false}
                    """
                        .formatted(account.getEmail(), authTestSupport.password())))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("CREDENTIAL_VERIFICATION_UNAVAILABLE"))
        .andExpect(cookie().doesNotExist("streamarr_access"))
        .andExpect(cookie().doesNotExist("streamarr_refresh"));

    assertThat(sessionRepository.findByAccountId(account.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should roll back the login session when completing its journal entry fails")
  void shouldRollBackLoginSessionWhenCompletingJournalEntryFails() {
    var command =
        LoginCommand.builder()
            .email(account.getEmail())
            .password(authTestSupport.password())
            .deviceName("journal-outage")
            .ipAddress("192.0.2.99")
            .build();

    assertThatThrownBy(() -> loginService.login(command))
        .isInstanceOf(CredentialAttemptUnavailableException.class);

    assertThat(sessionRepository.findByAccountId(account.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should hold no transaction or database connection when verifying a login password")
  void shouldHoldNoTransactionOrDatabaseConnectionWhenVerifyingLoginPassword() {
    passwordEncoder.afterNextMatch(
        () -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          assertThat(TransactionSynchronizationManager.hasResource(dataSource)).isFalse();
        });

    var result =
        loginService.login(
            LoginCommand.builder()
                .email(account.getEmail())
                .password(authTestSupport.password())
                .deviceName("connection-check")
                .ipAddress("192.0.2.99")
                .build());

    assertThat(sessionRepository.findByAccountId(account.getId()))
        .singleElement()
        .isEqualTo(result.session());
  }

  private void lockReservedAttempt() {
    try {
      journalLock =
          lockRow(
              RowLockTarget.builder()
                  .dataSource(dataSource)
                  .table("credential_attempt")
                  .keyColumn("account_id")
                  .rowId(account.getId())
                  .build());
    } catch (SQLException failure) {
      throw new AssertionError("Could not lock the reserved credential attempt", failure);
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class PasswordEncoderConfiguration {

    @Bean
    @Primary
    CompletionBlockingPasswordEncoder completionBlockingPasswordEncoder(
        @Qualifier("passwordEncoder") PasswordEncoder delegate) {
      return new CompletionBlockingPasswordEncoder(delegate);
    }
  }

  @RequiredArgsConstructor
  static final class CompletionBlockingPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final AtomicReference<Runnable> afterMatch = new AtomicReference<>();

    void afterNextMatch(Runnable action) {
      afterMatch.set(action);
    }

    void clear() {
      afterMatch.set(null);
    }

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      var matched = delegate.matches(rawPassword, encodedPassword);
      var action = afterMatch.getAndSet(null);
      if (action != null) {
        action.run();
      }

      return matched;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
      return delegate.upgradeEncoding(encodedPassword);
    }
  }
}
