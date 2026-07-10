package com.streamarr.server.services.auth;

import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Composes redemption, stored-context revalidation, and access-token minting for refresh. */
@Service
@RequiredArgsConstructor
public class TokenRefreshService {

  private final RefreshTokenService refreshTokenService;
  private final SessionScopeService sessionScopeService;
  private final AccessTokenIssuer accessTokenIssuer;
  private final UserAccountRepository userAccountRepository;

  public RefreshedTokens refresh(String rawToken) {
    var result = refreshTokenService.redeem(rawToken);

    var account =
        userAccountRepository
            .findById(result.session().getAccountId())
            .orElseThrow(InvalidRefreshTokenException::new);

    var context = sessionScopeService.revalidateStoredContext(account, result.session());
    var accessToken = accessTokenIssuer.issue(context);

    var rawRefreshToken =
        switch (result) {
          case RefreshResult.Rotated(String successor, _) -> successor;
          case RefreshResult.Replayed(String successor, _) -> successor;
          case RefreshResult.SupersededReplay _ -> null;
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
