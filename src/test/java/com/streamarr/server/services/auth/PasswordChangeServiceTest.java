package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Password Change Service Tests")
class PasswordChangeServiceTest {

  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final PasswordEncoder passwordEncoder = new TestPasswordEncoder();
  private final PasswordChangeCompletionService completionService =
      mock(PasswordChangeCompletionService.class);
  private final PasswordChangeService service =
      new PasswordChangeService(accountRepository, completionService, passwordEncoder);

  @Test
  @DisplayName("Should fail closed without issuing a token when account is missing")
  void shouldFailClosedWithoutIssuingTokenWhenAccountMissing() {
    var command =
        commandBuilder().accountId(UUID.randomUUID()).sessionId(UUID.randomUUID()).build();

    assertThatThrownBy(() -> service.changePassword(command))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should reject password change when current password is wrong")
  void shouldRejectPasswordChangeWhenCurrentPasswordIsWrong() {
    var currentPassword = UUID.randomUUID().toString();
    var account =
        accountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(currentPassword))
                .build());
    var command =
        commandBuilder()
            .accountId(account.getId())
            .sessionId(UUID.randomUUID())
            .currentPassword("wrong password")
            .build();

    assertThatThrownBy(() -> service.changePassword(command))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  private ChangePasswordCommand.ChangePasswordCommandBuilder commandBuilder() {
    return ChangePasswordCommand.builder()
        .currentPassword(UUID.randomUUID().toString())
        .newPassword(UUID.randomUUID().toString());
  }

  private static final class TestPasswordEncoder implements PasswordEncoder {

    @Override
    public String encode(CharSequence rawPassword) {
      return "encoded:" + rawPassword;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      return encode(rawPassword).equals(encodedPassword);
    }
  }
}
