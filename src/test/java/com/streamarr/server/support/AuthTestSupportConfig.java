package com.streamarr.server.support;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.RefreshTokenService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;

@TestConfiguration
public class AuthTestSupportConfig {

  @Bean
  public AuthTestSupport authTestSupport(
      UserAccountRepository userAccountRepository,
      HouseholdRepository householdRepository,
      HouseholdMembershipRepository membershipRepository,
      ProfileRepository profileRepository,
      AccountProfileRepository accountProfileRepository,
      RefreshTokenService refreshTokenService,
      AccessTokenIssuer accessTokenIssuer,
      AuthTokenProperties tokenProperties,
      PasswordEncoder passwordEncoder) {
    return new AuthTestSupport(
        userAccountRepository,
        householdRepository,
        membershipRepository,
        profileRepository,
        accountProfileRepository,
        refreshTokenService,
        accessTokenIssuer,
        AuthTestSupport.expiredIssuer(
            tokenProperties, membershipRepository, profileRepository, accountProfileRepository),
        passwordEncoder);
  }
}
