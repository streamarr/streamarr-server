package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("IntegrationTest")
@DisplayName("Password Change Login Race Integration Tests")
@Import(PasswordChangeLoginRaceIT.PasswordEncoderTestConfig.class)
class PasswordChangeLoginRaceIT extends AbstractIntegrationTest {

  @Autowired private LoginService loginService;
  @Autowired private PasswordChangeService passwordChangeService;
  @Autowired private RefreshTokenService refreshTokenService;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private PausingPasswordEncoder passwordEncoder;

  private UserAccount account;

  @AfterEach
  void deleteAccountAndCascades() {
    passwordEncoder.releaseLogin();
    if (account != null) {
      userAccountRepository.deleteById(account.getId());
    }
  }

  @Test
  @DisplayName(
      "Should reject an old-password login when password change completes after verification")
  void shouldRejectOldPasswordLoginWhenPasswordChangeCompletesAfterVerification() throws Exception {
    var oldPassword = UUID.randomUUID().toString();
    var newPassword = UUID.randomUUID().toString();
    account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(oldPassword))
                .build());
    var caller = refreshTokenService.createSession(account, "password-change-device").session();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var login =
          executor.submit(
              () -> {
                passwordEncoder.pauseAfterNextSuccessfulMatch();
                return loginService.login(
                    LoginCommand.builder()
                        .email(account.getEmail())
                        .password(oldPassword)
                        .deviceName("racing-login-device")
                        .source("race-test")
                        .build());
              });

      passwordEncoder.awaitLoginVerified();

      passwordChangeService.changePassword(
          ChangePasswordCommand.builder()
              .accountId(account.getId())
              .sessionId(caller.getId())
              .currentPassword(oldPassword)
              .newPassword(newPassword)
              .build());

      passwordEncoder.releaseLogin();

      assertThatThrownBy(() -> login.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(InvalidCredentialsException.class);
      assertThat(authSessionRepository.findByAccountId(account.getId()))
          .extracting("id")
          .containsExactly(caller.getId());
    } finally {
      passwordEncoder.releaseLogin();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class PasswordEncoderTestConfig {

    @Bean
    @Primary
    PausingPasswordEncoder pausingPasswordEncoder(
        @Qualifier("passwordEncoder") PasswordEncoder delegate) {
      return new PausingPasswordEncoder(delegate);
    }
  }

  static final class PausingPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final ThreadLocal<Boolean> pauseAfterMatch = ThreadLocal.withInitial(() -> false);
    private final CountDownLatch loginVerified = new CountDownLatch(1);
    private final CountDownLatch continueLogin = new CountDownLatch(1);

    private PausingPasswordEncoder(PasswordEncoder delegate) {
      this.delegate = delegate;
    }

    void pauseAfterNextSuccessfulMatch() {
      pauseAfterMatch.set(true);
    }

    void awaitLoginVerified() throws InterruptedException {
      assertThat(loginVerified.await(10, TimeUnit.SECONDS))
          .as("old-password login should reach the verified interleaving point")
          .isTrue();
    }

    void releaseLogin() {
      continueLogin.countDown();
    }

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      var matches = delegate.matches(rawPassword, encodedPassword);
      if (!matches || !pauseAfterMatch.get()) {
        return matches;
      }

      pauseAfterMatch.remove();
      loginVerified.countDown();
      awaitLoginRelease();
      return true;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
      return delegate.upgradeEncoding(encodedPassword);
    }

    private void awaitLoginRelease() {
      try {
        if (!continueLogin.await(10, TimeUnit.SECONDS)) {
          throw new AssertionError("password change did not release the paused login");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("paused login was interrupted", e);
      }
    }
  }
}
