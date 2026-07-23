package com.streamarr.server.services.auth;

import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Composes redemption, stored-context revalidation, and access-token minting for refresh. */
@Service
@RequiredArgsConstructor
public class TokenRefreshService {

  private final RefreshTokenService refreshTokenService;
  private final SessionScopeService sessionScopeService;
  private final AccessTokenIssuer accessTokenIssuer;
  private final UserAccountRepository userAccountRepository;

  /**
   * The whole refresh use case is one transaction: redemption joins it, so the session lock taken
   * during redemption is held through issuance — a concurrent logout waits, and a logout that
   * committed first refuses redemption. Any failure after rotation (disabled account, revalidation,
   * token encoding) rolls the rotation back instead of stranding the client's token family.
   */
  @Transactional
  public RefreshedTokens refresh(String rawToken) {
    var result = refreshTokenService.redeem(rawToken);

    var account =
        userAccountRepository
            .findById(result.session().getAccountId())
            .orElseThrow(InvalidRefreshTokenException::new);
    if (!account.isEnabled()) {
      throw new InvalidRefreshTokenException();
    }

    var context = sessionScopeService.revalidateStoredContext(account, result.session());
    var accessToken = accessTokenIssuer.issue(context);

    var rawRefreshToken =
        switch (result) {
          case RefreshResult.Rotated(String successor, _) -> successor;
          case RefreshResult.GraceRetry(String successor, _) -> successor;
          case RefreshResult.SupersededRetry _ -> null;
        };

    return new RefreshedTokens(accessToken, rawRefreshToken);
  }

  /** rawRefreshToken is absent only when a recovered successor has already been superseded. */
  public record RefreshedTokens(AccessToken accessToken, String rawRefreshToken) {

    public boolean carriesRefreshToken() {
      return rawRefreshToken != null;
    }

    @Override
    public String toString() {
      return "RefreshedTokens[accessToken=%s, rawRefreshToken=REDACTED]".formatted(accessToken);
    }
  }
}
