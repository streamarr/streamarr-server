package com.streamarr.server.support;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileDeletionAuthorizationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.auth.RefreshTokenService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;

@TestConfiguration
public class AuthTestSupportConfig {

  /**
   * Creates the authentication test support bean.
   *
   * @param tokenProperties configuration used to create an expired token issuer
   * @return configured authentication test support
   */
  @Bean
  public AuthTestSupport authTestSupport(
      UserAccountRepository userAccountRepository,
      HouseholdRepository householdRepository,
      ProfileRepository profileRepository,
      ProfileDeletionAuthorizationRepository deletionAuthorizationRepository,
      ProfileManagerRepository profileManagerRepository,
      ProfileHouseholdShareRepository profileShareRepository,
      RefreshTokenService refreshTokenService,
      AccessTokenIssuer accessTokenIssuer,
      JwtDecoder jwtDecoder,
      PlaybackTokenIssuer playbackTokenIssuer,
      AuthTokenProperties tokenProperties,
      PasswordEncoder passwordEncoder,
      PlatformTransactionManager transactionManager) {
    return new AuthTestSupport(
        userAccountRepository,
        householdRepository,
        profileRepository,
        deletionAuthorizationRepository,
        profileManagerRepository,
        profileShareRepository,
        refreshTokenService,
        accessTokenIssuer,
        AuthTestSupport.expiredIssuer(tokenProperties),
        jwtDecoder,
        playbackTokenIssuer,
        passwordEncoder,
        transactionManager);
  }
}
