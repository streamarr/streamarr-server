package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.Argon2Properties;
import com.streamarr.server.config.security.PasswordEncoderConfig;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("IntegrationTest")
@DisplayName("Login Service Transaction Integration Tests")
@Import(LoginServiceTransactionIT.ProbeConfiguration.class)
class LoginServiceTransactionIT extends AbstractIntegrationTest {

  private static final String PASSWORD = UUID.randomUUID().toString();

  @Autowired private LoginService loginService;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private TransactionProbePasswordEncoder passwordEncoder;

  private UserAccount account;

  @AfterEach
  void deleteAccountAndCascades() {
    if (account != null) {
      userAccountRepository.deleteById(account.getId());
    }
  }

  @Test
  @DisplayName("Should release database connection before password verification")
  void shouldReleaseDatabaseConnectionBeforePasswordVerification() {
    account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .build());
    passwordEncoder.resetProbe();

    loginService.login(
        LoginCommand.builder()
            .email(account.getEmail())
            .password(PASSWORD)
            .deviceName("transaction-probe")
            .source("127.0.0.1")
            .build());

    assertThat(passwordEncoder.sawTransactionBoundConnection()).isFalse();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeConfiguration {

    @Bean
    @Primary
    TransactionProbePasswordEncoder transactionProbePasswordEncoder(DataSource dataSource) {
      var delegate =
          new PasswordEncoderConfig()
              .passwordEncoder(
                  Argon2Properties.builder().memoryKib(4096).iterations(1).parallelism(1).build());
      return new TransactionProbePasswordEncoder(delegate, dataSource);
    }
  }

  static final class TransactionProbePasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final DataSource dataSource;
    private final AtomicBoolean transactionBoundConnection = new AtomicBoolean();

    TransactionProbePasswordEncoder(PasswordEncoder delegate, DataSource dataSource) {
      this.delegate = delegate;
      this.dataSource = dataSource;
    }

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      Connection connection = DataSourceUtils.getConnection(dataSource);
      try {
        transactionBoundConnection.set(
            DataSourceUtils.isConnectionTransactional(connection, dataSource));
      } finally {
        DataSourceUtils.releaseConnection(connection, dataSource);
      }
      return delegate.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
      return delegate.upgradeEncoding(encodedPassword);
    }

    void resetProbe() {
      transactionBoundConnection.set(false);
    }

    boolean sawTransactionBoundConnection() {
      return transactionBoundConnection.get();
    }
  }
}
