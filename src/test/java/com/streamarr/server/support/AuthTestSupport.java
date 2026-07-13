package com.streamarr.server.support;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.CreateAuthSessionCommand;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.auth.TokenContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Seeds real identities through production repositories and mints real tokens through the
 * production issuer. Never calls setup — the bootstrap claim belongs exclusively to the dedicated
 * setup tests. Unique emails per invocation: the shared container is never truncated.
 */
@RequiredArgsConstructor
public class AuthTestSupport {

  public static final String PASSWORD = "correct horse battery staple";

  private final UserAccountRepository userAccountRepository;
  private final HouseholdRepository householdRepository;
  private final HouseholdMembershipRepository membershipRepository;
  private final ProfileRepository profileRepository;
  private final AccountProfileRepository accountProfileRepository;
  private final RefreshTokenService refreshTokenService;
  private final AccessTokenIssuer accessTokenIssuer;
  private final AccessTokenIssuer expiredTokenIssuer;
  private final PasswordEncoder passwordEncoder;

  public TestIdentity createIdentity() {
    return createIdentity(AccountRole.USER);
  }

  public TestIdentity createAdminIdentity() {
    return createIdentity(AccountRole.ADMIN);
  }

  private TestIdentity createIdentity(AccountRole role) {
    var account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .accountRole(role)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    membershipRepository.grantMembership(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.OWNER)
            .build());
    var profile =
        profileRepository.saveAndFlush(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    accountProfileRepository.linkProfile(
        AccountProfile.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .profileId(profile.getId())
            .build());

    var issued =
        refreshTokenService.createSession(
            CreateAuthSessionCommand.builder()
                .accountId(account.getId())
                .deviceName("auth-test-support")
                .activeHouseholdId(household.getId())
                .activeProfileId(profile.getId())
                .build());

    return TestIdentity.builder()
        .account(account)
        .household(household)
        .profile(profile)
        .session(issued.session())
        .rawRefreshToken(issued.rawToken())
        .build();
  }

  public String accountBearer(TestIdentity identity) {
    return accessTokenIssuer
        .issue(contextBuilder(identity).householdId(null).profileId(null).build())
        .value();
  }

  public String householdBearer(TestIdentity identity) {
    return accessTokenIssuer.issue(contextBuilder(identity).profileId(null).build()).value();
  }

  public String profileBearer(TestIdentity identity) {
    return accessTokenIssuer.issue(contextBuilder(identity).build()).value();
  }

  /** A well-formed profile token whose lifetime already elapsed — minted on a fixed past clock. */
  public String expiredProfileBearer(TestIdentity identity) {
    return expiredTokenIssuer.issue(contextBuilder(identity).build()).value();
  }

  /** Deletes everything createIdentity made; FK cascades sweep memberships, links, sessions. */
  public void deleteIdentity(TestIdentity identity) {
    householdRepository.deleteById(identity.household().getId());
    userAccountRepository.deleteById(identity.account().getId());
  }

  public static RequestPostProcessor bearer(String token) {
    return request -> {
      request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
      return request;
    };
  }

  static AccessTokenIssuer expiredIssuer(
      AuthTokenProperties properties,
      HouseholdMembershipRepository membershipRepository,
      ProfileRepository profileRepository,
      AccountProfileRepository accountProfileRepository) {
    var cryptoConfig = new TokenCryptoConfig();
    // Rewind past the configured TTL so the minted token is expired even when
    // AUTH_ACCESS_TOKEN_TTL is raised in the environment running the tests.
    var pastClock =
        Clock.fixed(
            Instant.now().minus(properties.accessTokenTtl()).minus(Duration.ofMinutes(5)),
            ZoneOffset.UTC);
    return new AccessTokenIssuer(
        cryptoConfig.jwtEncoder(cryptoConfig.tokenSigningKeys(properties)),
        properties,
        pastClock,
        membershipRepository,
        profileRepository,
        accountProfileRepository);
  }

  private TokenContext.TokenContextBuilder contextBuilder(TestIdentity identity) {
    return TokenContext.builder()
        .account(identity.account())
        .session(identity.session())
        .householdId(identity.household().getId())
        .profileId(identity.profile().getId());
  }

  @Builder
  public record TestIdentity(
      UserAccount account,
      Household household,
      Profile profile,
      AuthSession session,
      String rawRefreshToken) {}
}
