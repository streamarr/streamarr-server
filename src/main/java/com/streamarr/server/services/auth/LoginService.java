package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoginService {

  private final UserAccountRepository userAccountRepository;
  private final RefreshTokenService refreshTokenService;
  private final PasswordEncoder passwordEncoder;
  private final LoginThrottle throttle;

  @Transactional
  public LoginResult login(LoginCommand command) {
    throttle.ensureAllowed(command.email(), command.source());

    var account = userAccountRepository.findByEmailIgnoreCase(command.email()).orElse(null);
    if (!credentialsValid(account, command.password())) {
      throttle.recordFailure(command.email(), command.source());
      throw new InvalidCredentialsException();
    }

    throttle.reset(command.email(), command.source());
    rehashIfUpgradeNeeded(account, command.password());

    var issued = refreshTokenService.createSession(account, command.deviceName());

    return LoginResult.builder()
        .account(account)
        .session(issued.session())
        .rawRefreshToken(issued.rawToken())
        .build();
  }

  private boolean credentialsValid(UserAccount account, String password) {
    return account != null
        && account.isEnabled()
        && passwordEncoder.matches(password, account.getPasswordHash());
  }

  private void rehashIfUpgradeNeeded(UserAccount account, String rawPassword) {
    if (!passwordEncoder.upgradeEncoding(account.getPasswordHash())) {
      return;
    }

    account.setPasswordHash(passwordEncoder.encode(rawPassword));
    userAccountRepository.save(account);
  }
}
