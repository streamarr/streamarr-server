package com.streamarr.server.support;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileDeletionAuthorization;
import com.streamarr.server.domain.auth.ProfileDeletionMode;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileDeletionAuthorizationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.CreateAuthSessionCommand;
import com.streamarr.server.services.auth.PlaybackTokenIssuer;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.auth.TokenContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds real identities through production repositories and mints real tokens through the
 * production issuer. Never calls setup — the bootstrap claim belongs exclusively to the dedicated
 * setup tests. Unique emails per invocation: the shared container is never truncated.
 */
@RequiredArgsConstructor
public class AuthTestSupport {

  private final String password = UUID.randomUUID().toString();

  private final UserAccountRepository userAccountRepository;
  private final HouseholdRepository householdRepository;
  private final ProfileRepository profileRepository;
  private final ProfileDeletionAuthorizationRepository deletionAuthorizationRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileHouseholdShareRepository profileShareRepository;
  private final RefreshTokenService refreshTokenService;
  private final AccessTokenIssuer accessTokenIssuer;
  private final AccessTokenIssuer expiredTokenIssuer;
  private final JwtDecoder jwtDecoder;
  private final PlaybackTokenIssuer playbackTokenIssuer;
  private final PasswordEncoder passwordEncoder;
  private final PlatformTransactionManager transactionManager;

  public TestIdentity createIdentity() {
    return createIdentity(AccountRole.USER);
  }

  public TestIdentity createAdminIdentity() {
    return createIdentity(AccountRole.ADMIN);
  }

  public String password() {
    return password;
  }

  public UserAccount createAccount() {
    return createAccount(AccountFixture.defaultAccountBuilder());
  }

  public UserAccount createAccount(UserAccount.UserAccountBuilder<?, ?> accountBuilder) {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var household =
                  householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
              return userAccountRepository.save(
                  accountBuilder
                      .homeHouseholdId(household.getId())
                      .householdRole(HouseholdRole.OWNER)
                      .build());
            });
  }

  public void deleteAccount(UserAccount account) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              userAccountRepository.deleteById(account.getId());
              userAccountRepository.flush();
              householdRepository.deleteById(account.getHomeHouseholdId());
            });
  }

  private TestIdentity createIdentity(AccountRole role) {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var household =
                  householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
              var account =
                  userAccountRepository.save(
                      AccountFixture.defaultAccountBuilder()
                          .accountRole(role)
                          .passwordHash(passwordEncoder.encode(password))
                          .homeHouseholdId(household.getId())
                          .householdRole(HouseholdRole.OWNER)
                          .build());
              var profile = profileRepository.save(ProfileFixture.defaultProfileBuilder().build());
              profileManagerRepository.save(
                  ProfileManager.builder()
                      .accountId(account.getId())
                      .profileId(profile.getId())
                      .build());
              profileShareRepository.save(
                  ProfileHouseholdShare.builder()
                      .profileId(profile.getId())
                      .householdId(household.getId())
                      .status(ProfileShareStatus.ACTIVE)
                      .build());

              var issued =
                  refreshTokenService.createSession(
                      CreateAuthSessionCommand.builder()
                          .accountId(account.getId())
                          .deviceName("auth-test-support")
                          .activeProfileId(profile.getId())
                          .build());

              return TestIdentity.builder()
                  .account(account)
                  .household(household)
                  .profile(profile)
                  .session(issued.session())
                  .rawRefreshToken(issued.rawToken())
                  .build();
            });
  }

  public String accountBearer(TestIdentity identity) {
    return accessTokenIssuer.issue(contextBuilder(identity).profileId(null).build()).value();
  }

  public String profileBearer(TestIdentity identity) {
    return accessTokenIssuer.issue(contextBuilder(identity).build()).value();
  }

  public String playbackBearer(TestIdentity identity, UUID streamSessionId) {
    var authenticatedIdentity =
        AuthenticatedIdentity.fromJwt(jwtDecoder.decode(profileBearer(identity)));
    var authority =
        PlaybackAuthority.builder()
            .authSessionId(identity.session().getId())
            .accountId(identity.account().getId())
            .householdId(identity.household().getId())
            .profileId(identity.profile().getId())
            .build();
    var ownedSession =
        StreamSessionFixture.defaultSessionBuilder()
            .sessionId(streamSessionId)
            .authority(authority)
            .build();
    return playbackTokenIssuer
        .issue(authenticatedIdentity, authority, ownedSession, Duration.ofHours(1))
        .value();
  }

  /** A well-formed profile token whose lifetime already elapsed — minted on a fixed past clock. */
  public String expiredProfileBearer(TestIdentity identity) {
    return expiredTokenIssuer.issue(contextBuilder(identity).build()).value();
  }

  /** Deletes everything createIdentity made in one transaction so deferred invariants hold. */
  public void deleteIdentity(TestIdentity identity) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              profileShareRepository
                  .findByProfileIdAndHouseholdId(
                      identity.profile().getId(), identity.household().getId())
                  .ifPresent(profileShareRepository::delete);
              profileShareRepository.flush();
              deletionAuthorizationRepository.saveAndFlush(
                  ProfileDeletionAuthorization.builder()
                      .profileId(identity.profile().getId())
                      .actingAccountId(identity.account().getId())
                      .mode(ProfileDeletionMode.ORDINARY)
                      .build());
              profileRepository.deleteById(identity.profile().getId());
              userAccountRepository.deleteById(identity.account().getId());
              householdRepository.deleteById(identity.household().getId());
            });
  }

  public static RequestPostProcessor bearer(String token) {
    return request -> {
      request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
      return request;
    };
  }

  static AccessTokenIssuer expiredIssuer(AuthTokenProperties properties) {
    var cryptoConfig = new TokenCryptoConfig();
    // Rewind past the configured TTL so the minted token is expired even when
    // AUTH_ACCESS_TOKEN_TTL is raised in the environment running the tests.
    var pastClock =
        Clock.fixed(
            Instant.now().minus(properties.accessTokenTtl()).minus(Duration.ofMinutes(5)),
            ZoneOffset.UTC);
    return new AccessTokenIssuer(
        cryptoConfig.jwtEncoder(cryptoConfig.tokenSigningKeys(properties)), properties, pastClock);
  }

  private TokenContext.TokenContextBuilder contextBuilder(TestIdentity identity) {
    return TokenContext.builder()
        .account(identity.account())
        .session(identity.session())
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
