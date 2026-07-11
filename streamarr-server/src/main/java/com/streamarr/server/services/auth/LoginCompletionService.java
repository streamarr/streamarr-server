package com.streamarr.server.services.auth;

import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoginCompletionService {

  private final UserAccountRepository userAccountRepository;
  private final RefreshTokenService refreshTokenService;

  @Transactional
  public LoginResult complete(LoginCompletionCommand command) {
    if (!userAccountRepository.lockIfCredentialsUnchanged(
        command.accountId(), command.expectedPasswordHash())) {
      throw new InvalidCredentialsException();
    }

    var account =
        userAccountRepository
            .findById(command.accountId())
            .orElseThrow(InvalidCredentialsException::new);
    command.upgradedPasswordHash().ifPresent(account::setPasswordHash);

    var issued = refreshTokenService.createSession(account, command.deviceName());

    return LoginResult.builder()
        .account(account)
        .session(issued.session())
        .rawRefreshToken(issued.rawToken())
        .build();
  }
}
