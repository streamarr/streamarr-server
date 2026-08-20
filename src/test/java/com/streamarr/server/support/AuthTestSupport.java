package com.streamarr.server.support;

import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.ServerBootstrapRepository;
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
import java.util.function.UnaryOperator;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a complete ADR 0024 identity for integration tests — Household, unrestricted Adult
 * Personal Profile, HouseholdAdmin Account, structural share, and a session — and mints tokens for
 * it. The identity is created in one transaction because the deferred invariant triggers check the
 * whole shape at commit.
 */
@RequiredArgsConstructor
public class AuthTestSupport {

  private final String password = UUID.randomUUID().toString();

  private final DSLContext dsl;
  private final UserAccountRepository userAccountRepository;
  private final ServerBootstrapRepository serverBootstrapRepository;
  private final HouseholdRepository householdRepository;
  private final ProfileRepository profileRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final RefreshTokenService refreshTokenService;
  private final AccessTokenIssuer accessTokenIssuer;
  private final AccessTokenIssuer expiredTokenIssuer;
  private final JwtDecoder jwtDecoder;
  private final PlaybackTokenIssuer playbackTokenIssuer;
  private final PasswordEncoder passwordEncoder;
  private final TransactionTemplate transactionTemplate;

  /**
   * Fixture-created identities skip the setup ceremony, so the bootstrap claim it would have made
   * is missing and pairing issuance refuses with SETUP_INCOMPLETE. Idempotent; tests that need an
   * unclaimed server delete SERVER_BOOTSTRAP themselves, as before.
   */
  private TestIdentity bootstrapAdmin;

  public void claimBootstrap() {
    // Earlier test classes can leave either half behind — a claim whose admins were deleted, or
    // admins without a claim — so both halves are ensured independently. A claim without an
    // enabled ServerAdmin is a state production can never reach, and T4 enforces it here too.
    if (bootstrapAdmin == null && !hasEnabledServerAdmin()) {
      bootstrapAdmin = createAdminIdentity();
    }
    serverBootstrapRepository.claim(
        bootstrapAdmin == null ? null : bootstrapAdmin.account().getId());
  }

  private boolean hasEnabledServerAdmin() {
    return dsl.fetchExists(
        dsl.selectFrom(USER_ACCOUNT)
            .where(USER_ACCOUNT.SERVER_ADMIN.isTrue().and(USER_ACCOUNT.ENABLED.isTrue())));
  }

  /**
   * Restores the shared database's historical unclaimed baseline. Call before deleting identities:
   * T4 (an enabled ServerAdmin must remain) only enforces while a claim exists, and fixture cleanup
   * routinely deletes the last admin.
   */
  public void unclaimBootstrap() {
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();
    if (bootstrapAdmin != null) {
      deleteIdentity(bootstrapAdmin);
      bootstrapAdmin = null;
    }
  }

  public TestIdentity createIdentity() {
    return createIdentity(false);
  }

  /** A ServerAdmin (live row) who is also a HouseholdAdmin of its own Household. */
  public TestIdentity createAdminIdentity() {
    return createIdentity(true);
  }

  public String password() {
    return password;
  }

  /**
   * A complete Account (Household, unrestricted Adult Personal Profile, structural share) with no
   * session, customized by the caller. Deleting it again goes through {@link #deleteAccount}.
   */
  public UserAccount createAccount(UnaryOperator<UserAccount.UserAccountBuilder<?, ?>> customize) {
    return transactionTemplate.execute(
        _ -> {
          var household =
              householdRepository.saveAndFlush(HouseholdFixture.defaultHouseholdBuilder().build());
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
          var account =
              userAccountRepository.saveAndFlush(
                  customize
                      .apply(
                          AccountFixture.defaultAccountBuilder()
                              .householdId(household.getId())
                              .householdRole(HouseholdRole.ADMIN)
                              .personalProfileId(profile.getId())
                              .passwordHash(passwordEncoder.encode(password)))
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(household.getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }

  public UserAccount createAccount() {
    return createAccount(UnaryOperator.identity());
  }

  /**
   * Deletes an Account's whole Household in one transaction — T1 forbids a Household losing its
   * final Account except inside teardown, so every Account and Profile of the Household goes with
   * it (a teardown in miniature). Manager rows, shares, and guard rows cascade.
   */
  public void deleteAccount(UUID accountId) {
    transactionTemplate.executeWithoutResult(
        _ ->
            userAccountRepository
                .findById(accountId)
                .ifPresent(
                    account -> {
                      var householdId = account.getHouseholdId();
                      userAccountRepository.deleteAll(
                          userAccountRepository.findByHouseholdId(householdId));
                      userAccountRepository.flush();
                      profileRepository.deleteAll(profileRepository.findByHouseholdId(householdId));
                      profileRepository.flush();
                      householdRepository.deleteById(householdId);
                    }));
  }

  private TestIdentity createIdentity(boolean serverAdmin) {
    return transactionTemplate.execute(
        _ -> {
          var household =
              householdRepository.saveAndFlush(HouseholdFixture.defaultHouseholdBuilder().build());
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
          var account =
              userAccountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .serverAdmin(serverAdmin)
                      .householdId(household.getId())
                      .householdRole(HouseholdRole.ADMIN)
                      .personalProfileId(profile.getId())
                      .passwordHash(passwordEncoder.encode(password))
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(household.getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());

          var issued =
              refreshTokenService.createSession(
                  CreateAuthSessionCommand.builder()
                      .accountId(account.getId())
                      .deviceName("auth-test-support")
                      .contextHouseholdId(household.getId())
                      .selectedProfileId(profile.getId())
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

  /** An Account-scoped token: at the Profile picker of the membership Household. */
  public String accountBearer(TestIdentity identity) {
    return accessTokenIssuer.issue(contextBuilder(identity).profileId(null).build()).value();
  }

  /** An Account-scoped token that just passed the reauthentication ceremony. */
  public String freshAccountBearer(TestIdentity identity) {
    return accessTokenIssuer
        .issueReauthenticated(
            contextBuilder(identity).profileId(null).build(),
            Instant.now().plus(Duration.ofMinutes(10)))
        .value();
  }

  public String profileBearer(TestIdentity identity) {
    return accessTokenIssuer.issue(contextBuilder(identity).build()).value();
  }

  public String playbackBearer(TestIdentity identity, UUID streamSessionId) {
    var authenticatedIdentity =
        AuthenticatedIdentity.fromJwt(jwtDecoder.decode(profileBearer(identity)));
    var ownedSession =
        StreamSessionFixture.defaultSessionBuilder()
            .sessionId(streamSessionId)
            .authority(authenticatedIdentity.playbackAuthority())
            .build();
    return playbackTokenIssuer
        .issue(authenticatedIdentity, ownedSession, Duration.ofHours(1))
        .value();
  }

  /** A well-formed profile token whose lifetime already elapsed — minted on a fixed past clock. */
  public String expiredProfileBearer(TestIdentity identity) {
    return expiredTokenIssuer.issue(contextBuilder(identity).build()).value();
  }

  /**
   * Deletes everything createIdentity made. The Account goes first (its FK to the Profile and the
   * deferred T1/T2 triggers are satisfied once the Household is gone in the same transaction).
   */
  public void deleteIdentity(TestIdentity identity) {
    deleteAccount(identity.account().getId());
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
        .contextHouseholdId(identity.household().getId())
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
