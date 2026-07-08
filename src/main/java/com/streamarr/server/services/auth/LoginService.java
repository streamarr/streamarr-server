package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginService {

  private final UserAccountRepository userAccountRepository;
  private final RefreshTokenService refreshTokenService;
  private final PasswordEncoder passwordEncoder;
  private final LoginThrottle throttle;
  private final String timingEqualizerHash;

  public LoginService(
      UserAccountRepository userAccountRepository,
      RefreshTokenService refreshTokenService,
      PasswordEncoder passwordEncoder,
      LoginThrottle throttle) {
    this.userAccountRepository = userAccountRepository;
    this.refreshTokenService = refreshTokenService;
    this.passwordEncoder = passwordEncoder;
    this.throttle = throttle;
    this.timingEqualizerHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  @Transactional
  public LoginResult login(LoginCommand command) {
    // Reserve the slot before any password work — recording failures after hashing is a
    // check-then-act race that lets a concurrent burst overrun the budget.
    throttle.registerAttempt(command.email(), command.source());

    var account = userAccountRepository.findByEmailIgnoreCase(command.email()).orElse(null);
    if (!credentialsValid(account, command.password())) {
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
    if (account == null || !account.isEnabled()) {
      // Burn the same Argon2 cost as a real comparison so response timing cannot disclose
      // whether the email exists or the account is enabled.
      passwordEncoder.matches(password, timingEqualizerHash);
      return false;
    }
    return passwordEncoder.matches(password, account.getPasswordHash());
  }

  private void rehashIfUpgradeNeeded(UserAccount account, String rawPassword) {
    if (!passwordEncoder.upgradeEncoding(account.getPasswordHash())) {
      return;
    }

    account.setPasswordHash(passwordEncoder.encode(rawPassword));
    userAccountRepository.save(account);
  }
}
