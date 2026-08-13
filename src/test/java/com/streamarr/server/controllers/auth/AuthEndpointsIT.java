package com.streamarr.server.controllers.auth;

import static com.streamarr.server.jooq.generated.tables.Household.HOUSEHOLD;
import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static com.streamarr.server.jooq.generated.tables.ProfileManager.PROFILE_MANAGER;
import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;
import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.AuthCookies;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
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
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileDeletionAuthorizationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.auth.TokenClaims;
import com.streamarr.server.services.auth.TokenContext;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@DisplayName("Auth Endpoints Integration Tests")
class AuthEndpointsIT extends AbstractIntegrationTest {

  private static final String PASSWORD = UUID.randomUUID().toString();

  @Autowired private MockMvc mockMvc;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private ProfileRepository profileRepository;

  @Autowired private ProfileManagerRepository profileManagerRepository;

  @Autowired private ProfileHouseholdShareRepository profileShareRepository;

  @Autowired private ProfileDeletionAuthorizationRepository deletionAuthorizationRepository;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private DSLContext dsl;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private AuthTokenProperties tokenProperties;

  @Autowired private RefreshTokenService refreshTokenService;

  @Autowired private JwtEncoder jwtEncoder;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired
  private com.streamarr.server.repositories.auth.ServerBootstrapRepository
      serverBootstrapRepository;

  private UserAccount account;
  private Household household;
  private Profile profile;
  private String setupEmail;
  private String setupHouseholdName;
  private final List<UUID> additionalCleanupAccountIds = new ArrayList<>();

  @AfterEach
  void deleteIdentityGraph() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              dsl.deleteFrom(SERVER_BOOTSTRAP).execute();
              deleteIdentityByEmail(setupEmail);
              if (account != null) {
                deleteIdentityByAccountId(account.getId());
              }
              additionalCleanupAccountIds.forEach(this::deleteIdentityByAccountId);
            });
  }

  @Test
  @DisplayName("Should return refresh cookie scoped to refresh path when login in cookie mode")
  void shouldReturnRefreshCookieScopedToRefreshPathWhenLoginInCookieMode() throws Exception {
    seedSingleProfileIdentity();

    var response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"email": "%s", "password": "%s", "deviceName": "it-device", \
                        "cookieMode": true}
                        """
                            .formatted(account.getEmail(), PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessTokenExpiresAt").exists())
            .andExpect(jsonPath("$.scope").value("account"))
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andReturn()
            .getResponse();

    var accessCookie = response.getCookie("streamarr_access");
    assertThat(accessCookie).isNotNull();
    assertThat(accessCookie.getPath()).isEqualTo("/");
    assertThat(accessCookie.isHttpOnly()).isTrue();
    assertThat(accessCookie.getSecure()).isTrue();
    assertThat(sameSiteOf(accessCookie)).isEqualTo("Strict");
    assertThat(accessCookie.getValue()).isNotBlank();
    assertThat(accessCookie.getMaxAge())
        .isEqualTo(Math.toIntExact(tokenProperties.refreshTokenTtl().toSeconds()));

    var refreshCookie = response.getCookie("streamarr_refresh");
    assertThat(refreshCookie).isNotNull();
    assertThat(refreshCookie.getPath()).isEqualTo("/api/auth/refresh");
    assertThat(refreshCookie.isHttpOnly()).isTrue();
    assertThat(refreshCookie.getSecure()).isTrue();
    assertThat(sameSiteOf(refreshCookie)).isEqualTo("Strict");
    assertThat(refreshCookie.getValue()).isNotBlank();
    assertThat(refreshCookie.getMaxAge())
        .isEqualTo(Math.toIntExact(tokenProperties.refreshTokenTtl().toSeconds()));
    assertUncacheable(response);
  }

  @Test
  @DisplayName("Should create identity when setup is first")
  void shouldCreateIdentityWhenSetupIsFirst() throws Exception {
    var suffix = UUID.randomUUID();
    setupEmail = "setup-" + suffix + "@example.com";
    setupHouseholdName = "Home-" + suffix;

    mockMvc
        .perform(
            post("/api/auth/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(setupBody(setupEmail)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.refreshToken").isNotEmpty())
        .andExpect(jsonPath("$.accessTokenExpiresAt").exists())
        .andExpect(jsonPath("$.scope").value("account"));
  }

  @Test
  @DisplayName("Should reject setup when already completed")
  void shouldRejectSetupWhenAlreadyCompleted() throws Exception {
    var suffix = UUID.randomUUID();
    setupEmail = "setup-" + suffix + "@example.com";
    setupHouseholdName = "Home-" + suffix;
    mockMvc
        .perform(
            post("/api/auth/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(setupBody(setupEmail)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post("/api/auth/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(setupBody("second-" + suffix + "@example.com")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETUP_ALREADY_COMPLETED"));
  }

  @Test
  @DisplayName("Should throttle login when failures exceed limit")
  void shouldThrottleLoginWhenFailuresExceedLimit() throws Exception {
    seedSingleProfileIdentity();
    var throttledSource =
        "10.99."
            + ThreadLocalRandom.current().nextInt(250)
            + "."
            + ThreadLocalRandom.current().nextInt(250);

    for (int i = 0; i < 5; i++) {
      mockMvc
          .perform(
              post("/api/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(loginBody(account.getEmail(), "wrong-password-" + i))
                  .with(
                      request -> {
                        request.setRemoteAddr(throttledSource);
                        return request;
                      }))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(account.getEmail(), PASSWORD))
                .with(
                    request -> {
                      request.setRemoteAddr(throttledSource);
                      return request;
                    }))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
  }

  @Test
  @DisplayName("Should recover same refresh token when rotation response lost")
  void shouldRecoverSameRefreshTokenWhenRotationResponseLost() throws Exception {
    seedSingleProfileIdentity();
    var firstRefreshToken = loginAndReturnRefreshToken();

    var rotatedRefreshToken = redeemAndReturnRefreshToken(firstRefreshToken);

    // Treat the first rotation response as lost. Retrying the consumed predecessor inside the
    // grace window must recover that exact successor rather than strand the client on A.
    var replayedRefreshToken = redeemAndReturnRefreshToken(firstRefreshToken);

    assertThat(rotatedRefreshToken).isNotBlank().isNotEqualTo(firstRefreshToken);
    assertThat(replayedRefreshToken).isEqualTo(rotatedRefreshToken);
  }

  @Test
  @DisplayName("Should recover same refresh cookie when rotation response lost")
  void shouldRecoverSameRefreshCookieWhenRotationResponseLost() throws Exception {
    seedSingleProfileIdentity();
    var loginResponse = cookieModeLogin();
    var predecessor = loginResponse.getCookie("streamarr_refresh");
    var csrfCookie = loginResponse.getCookie(AuthCookies.CSRF_COOKIE);

    var rotated =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .cookie(predecessor, csrfCookie)
                    .header("X-XSRF-TOKEN", csrfCookie.getValue()))
            .andExpect(status().isOk())
            .andExpect(cookie().exists("streamarr_access"))
            .andExpect(cookie().exists("streamarr_refresh"))
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andReturn()
            .getResponse()
            .getCookie("streamarr_refresh");

    var replayed =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .cookie(predecessor, csrfCookie)
                    .header("X-XSRF-TOKEN", csrfCookie.getValue()))
            .andExpect(status().isOk())
            .andExpect(cookie().exists("streamarr_access"))
            .andExpect(cookie().exists("streamarr_refresh"))
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andReturn()
            .getResponse()
            .getCookie("streamarr_refresh");

    assertThat(replayed.getValue()).isEqualTo(rotated.getValue());
  }

  @Test
  @DisplayName("Should keep explicit body refresh in body mode when auth cookies are present")
  void shouldKeepExplicitBodyRefreshInBodyModeWhenAuthCookiesPresent() throws Exception {
    seedSingleProfileIdentity();
    var bodyRefreshToken = loginAndReadField("refreshToken");
    var cookieLogin = cookieModeLogin();
    var refreshCookie = cookieLogin.getCookie("streamarr_refresh");
    var csrfCookie = cookieLogin.getCookie(AuthCookies.CSRF_COOKIE);

    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"refreshToken\": \"%s\", \"cookieMode\": true}".formatted(bodyRefreshToken))
                .cookie(refreshCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.refreshToken").isNotEmpty())
        .andExpect(cookie().doesNotExist("streamarr_access"))
        .andExpect(cookie().doesNotExist("streamarr_refresh"));
  }

  @Test
  @DisplayName("Should reject issued profile token immediately when active share is removed")
  void shouldRejectIssuedProfileTokenImmediatelyWhenActiveShareRemoved() throws Exception {
    seedSingleProfileIdentity();
    var accessToken = selectProfileToken(loginAndReadField("accessToken"));

    // Control: the fresh profile token authenticates (probe passes the filter, then 404s).
    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isNotFound());

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ ->
                profileShareRepository
                    .findByProfileIdAndHouseholdId(profile.getId(), household.getId())
                    .ifPresent(profileShareRepository::delete));

    // Live authority is resolved from the current account home and active share on every request.
    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should reject issued profile token immediately when account home changes")
  void shouldRejectIssuedProfileTokenImmediatelyWhenAccountHomeChanges() throws Exception {
    seedSingleProfileIdentity();
    var accessToken = selectProfileToken(loginAndReadField("accessToken"));

    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isNotFound());

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              var movedAccount = userAccountRepository.findById(account.getId()).orElseThrow();
              movedAccount.setHouseholdRole(HouseholdRole.PARENT);
              userAccountRepository.saveAndFlush(movedAccount);
              var replacementOwner =
                  userAccountRepository.save(
                      AccountFixture.defaultAccountBuilder()
                          .email("replacement-owner-" + UUID.randomUUID() + "@example.com")
                          .homeHouseholdId(household.getId())
                          .householdRole(HouseholdRole.OWNER)
                          .build());
              additionalCleanupAccountIds.add(replacementOwner.getId());

              var nextHousehold =
                  householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
              var nextOwner =
                  userAccountRepository.save(
                      AccountFixture.defaultAccountBuilder()
                          .email("next-owner-" + UUID.randomUUID() + "@example.com")
                          .homeHouseholdId(nextHousehold.getId())
                          .householdRole(HouseholdRole.OWNER)
                          .build());
              additionalCleanupAccountIds.add(nextOwner.getId());
              movedAccount.setHomeHouseholdId(nextHousehold.getId());
              movedAccount.setHouseholdRole(HouseholdRole.MEMBER);
              userAccountRepository.save(movedAccount);
            });

    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should return expired token code when bearer expired")
  void shouldReturnExpiredTokenCodeWhenBearerExpired() throws Exception {
    seedSingleProfileIdentity();

    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + expiredAccessToken()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("EXPIRED_TOKEN"));
  }

  @Test
  @DisplayName("Should return invalid token when a signed identity claim is malformed")
  void shouldReturnInvalidTokenWhenSignedIdentityClaimMalformed() throws Exception {
    seedSingleProfileIdentity();
    var session = refreshTokenService.createSession(account, "malformed-identity-test").session();
    var malformedToken = signedAccessToken(session, claims -> claims.subject("not-a-uuid"));

    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + malformedToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
  }

  @Test
  @DisplayName("Should return invalid token when signed scope and identity claims disagree")
  void shouldReturnInvalidTokenWhenSignedScopeAndIdentityClaimsDisagree() throws Exception {
    seedSingleProfileIdentity();
    var session = refreshTokenService.createSession(account, "incoherent-identity-test").session();
    var incoherentToken =
        signedAccessToken(session, claims -> claims.claim(TokenClaims.SCOPE, "profile"));

    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + incoherentToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
  }

  @Test
  @DisplayName("Should refresh when access cookie expired but refresh cookie valid")
  void shouldRefreshWhenAccessCookieExpiredButRefreshCookieValid() throws Exception {
    seedSingleProfileIdentity();
    var loginResponse =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"email": "%s", "password": "%s", "deviceName": "it-device", \
                        "cookieMode": true}
                        """
                            .formatted(account.getEmail(), PASSWORD)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var refreshCookie = loginResponse.getCookie("streamarr_refresh");
    var csrfCookie = loginResponse.getCookie(AuthCookies.CSRF_COOKIE);

    // Browsers attach the Path=/ access cookie to every request — including refresh. An expired
    // access credential must never deadlock renewal into logout.
    var rotated =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .cookie(
                        new Cookie("streamarr_access", expiredAccessToken()),
                        refreshCookie,
                        csrfCookie)
                    .header("X-XSRF-TOKEN", csrfCookie.getValue()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessTokenExpiresAt").exists())
            .andExpect(jsonPath("$.scope").value("account"))
            .andReturn()
            .getResponse();

    assertUncacheable(rotated);
    assertThat(rotated.getCookie("streamarr_access")).isNotNull();
    assertThat(rotated.getCookie("streamarr_refresh")).isNotNull();
    var successor = rotated.getCookie("streamarr_refresh").getValue();
    assertThat(successor).isNotEqualTo(refreshCookie.getValue());

    var graceReplay =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .cookie(refreshCookie, csrfCookie)
                    .header("X-XSRF-TOKEN", csrfCookie.getValue()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    assertThat(graceReplay.getCookie("streamarr_access")).isNotNull();
    assertThat(graceReplay.getCookie("streamarr_refresh")).isNotNull();
    assertThat(graceReplay.getCookie("streamarr_refresh").getValue()).isEqualTo(successor);
  }

  @Test
  @DisplayName("Should preserve profile scope when refreshing")
  void shouldPreserveProfileScopeWhenRefreshing() throws Exception {
    seedSingleProfileIdentity();
    var login = objectMapper.readTree(loginResponseBody());
    var refreshToken = login.get("refreshToken").asString();
    selectProfileToken(login.get("accessToken").asString());

    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(refreshToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scope").value("profile"));
  }

  @Test
  @DisplayName("Should downgrade to account scope when stored context no longer valid")
  void shouldDowngradeToAccountScopeWhenStoredContextNoLongerValid() throws Exception {
    seedSingleProfileIdentity();
    var login = objectMapper.readTree(loginResponseBody());
    var refreshToken = login.get("refreshToken").asString();

    selectProfileToken(login.get("accessToken").asString());
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ ->
                profileShareRepository
                    .findByProfileIdAndHouseholdId(profile.getId(), household.getId())
                    .ifPresent(profileShareRepository::delete));

    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(refreshToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scope").value("account"));
  }

  @Test
  @DisplayName("Should upgrade to profile scope when profile selected")
  void shouldUpgradeToProfileScopeWhenProfileSelected() throws Exception {
    var accountToken = accountScopedTokenWithTwoProfiles();

    var response =
        mockMvc
            .perform(
                post("/api/auth/select-profile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accountToken)
                    .content(
                        "{\"profileId\": \"%s\", \"cookieMode\": false}"
                            .formatted(profile.getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("profile"))
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    var claims = decodeToken(objectMapper.readTree(response).get("accessToken").asString());
    assertThat(claims.getClaimAsString("pf")).isEqualTo(profile.getId().toString());
    assertThat(claims.hasClaim("hh")).isFalse();
  }

  @Test
  @DisplayName("Should prefer bearer response when profile selection also carries access cookie")
  void shouldPreferBearerResponseWhenProfileSelectionAlsoCarriesAccessCookie() throws Exception {
    var accountToken = accountScopedTokenWithTwoProfiles();
    var accessCookie = cookieModeLogin().getCookie("streamarr_access");

    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accountToken)
                .cookie(accessCookie)
                .content(
                    "{\"profileId\": \"%s\", \"cookieMode\": true}".formatted(profile.getId())))
        .andExpect(status().isOk())
        .andExpect(cookie().doesNotExist("streamarr_access"))
        .andExpect(cookie().doesNotExist("streamarr_refresh"))
        .andExpect(jsonPath("$.accessToken").isNotEmpty());
  }

  @Test
  @DisplayName("Should never expose an access token body when a browser is cookie authenticated")
  void shouldNeverExposeAccessTokenBodyWhenBrowserIsCookieAuthenticated() throws Exception {
    seedSingleProfileIdentity();
    createPortableProfile();
    var loginResponse = cookieModeLogin();
    var accessCookie = loginResponse.getCookie("streamarr_access");
    var csrfCookie = loginResponse.getCookie(AuthCookies.CSRF_COOKIE);

    var response =
        mockMvc
            .perform(
                post("/api/auth/select-profile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .cookie(accessCookie, csrfCookie)
                    .header("X-XSRF-TOKEN", csrfCookie.getValue())
                    .content(
                        "{\"profileId\": \"%s\", \"cookieMode\": false}"
                            .formatted(profile.getId())))
            .andExpect(status().isOk())
            .andExpect(cookie().exists("streamarr_access"))
            .andExpect(cookie().doesNotExist("streamarr_refresh"))
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andReturn()
            .getResponse();

    assertUncacheable(response);
  }

  @Test
  @DisplayName("Should reject profile selection when profile id missing")
  void shouldRejectProfileSelectionWhenProfileIdMissing() throws Exception {
    var accountToken = accountScopedTokenWithTwoProfiles();

    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accountToken)
                .content("{\"cookieMode\": false}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Should reject profile selection when profile not linked")
  void shouldRejectProfileSelectionWhenProfileNotLinked() throws Exception {
    var accountToken = accountScopedTokenWithTwoProfiles();

    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accountToken)
                .content(
                    "{\"profileId\": \"%s\", \"cookieMode\": false}"
                        .formatted(java.util.UUID.randomUUID())))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should not advance expiry when selection repeated")
  void shouldNotAdvanceExpiryWhenSelectionRepeated() throws Exception {
    var sourceToken = accountScopedTokenWithTwoProfiles();

    var firstToken = selectProfileToken(sourceToken);
    var firstExpiry = decodeToken(firstToken).getExpiresAt();

    // JWT timestamps carry whole seconds; cross a second boundary so an uncapped reissue would
    // visibly advance the expiry. Selection derives authority — it must never extend it.
    Awaitility.await().pollDelay(Duration.ofMillis(1100)).until(() -> true);

    var secondToken = selectProfileToken(firstToken);
    var secondExpiry = decodeToken(secondToken).getExpiresAt();

    assertThat(secondExpiry).isEqualTo(firstExpiry);
  }

  private String selectProfileToken(String bearerToken) throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/auth/select-profile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + bearerToken)
                    .content(
                        "{\"profileId\": \"%s\", \"cookieMode\": false}"
                            .formatted(profile.getId())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).get("accessToken").asString();
  }

  private String accountScopedTokenWithTwoProfiles() throws Exception {
    seedSingleProfileIdentity();
    createPortableProfile();

    var response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(account.getEmail(), PASSWORD)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("account"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).get("accessToken").asString();
  }

  @Test
  @DisplayName("Should reject cookie authenticated post when csrf token missing")
  void shouldRejectCookieAuthenticatedPostWhenCsrfTokenMissing() throws Exception {
    seedSingleProfileIdentity();
    var accessCookie = cookieModeLogin().getCookie("streamarr_access");

    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(accessCookie)
                .content(
                    "{\"profileId\": \"%s\", \"cookieMode\": true}".formatted(profile.getId())))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should keep profile selection in the cookie response when cookie authenticated")
  void shouldKeepProfileSelectionInCookieResponseWhenCookieAuthenticated() throws Exception {
    seedSingleProfileIdentity();
    var loginResponse = cookieModeLogin();
    var accessCookie = loginResponse.getCookie("streamarr_access");
    var csrfCookie = loginResponse.getCookie(AuthCookies.CSRF_COOKIE);

    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(accessCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .content(
                    "{\"profileId\": \"%s\", \"cookieMode\": false}".formatted(profile.getId())))
        .andExpect(status().isOk())
        .andExpect(cookie().exists("streamarr_access"))
        .andExpect(cookie().doesNotExist("streamarr_refresh"))
        .andExpect(jsonPath("$.accessToken").doesNotExist());
  }

  @Test
  @DisplayName("Should accept bearer post when csrf token absent")
  void shouldAcceptBearerPostWhenCsrfTokenAbsent() throws Exception {
    seedSingleProfileIdentity();
    var accessToken = loginAndReadField("accessToken");

    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(
                    "{\"profileId\": \"%s\", \"cookieMode\": false}".formatted(profile.getId())))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should reject refresh when only refresh cookie and no csrf token")
  void shouldRejectRefreshWhenOnlyRefreshCookieAndNoCsrfToken() throws Exception {
    seedSingleProfileIdentity();
    var refreshCookie = cookieModeLogin().getCookie("streamarr_refresh");

    // Browsers drop the expired access cookie; the matcher must still treat the ambient refresh
    // cookie as an authentication carrier.
    mockMvc
        .perform(post("/api/auth/refresh").cookie(refreshCookie))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should refresh when only refresh cookie and csrf token present")
  void shouldRefreshWhenOnlyRefreshCookieAndCsrfTokenPresent() throws Exception {
    seedSingleProfileIdentity();
    var loginResponse = cookieModeLogin();
    var refreshCookie = loginResponse.getCookie("streamarr_refresh");
    var csrfCookie = loginResponse.getCookie(AuthCookies.CSRF_COOKIE);
    assertThat(csrfCookie).isNotNull();

    // The page reads the CSRF cookie and echoes its raw value — the SW contract.
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .cookie(refreshCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scope").value("account"));
  }

  @Test
  @DisplayName("Should revoke all other refresh sessions when password changed")
  void shouldRevokeAllOtherSessionsWhenPasswordChanged() throws Exception {
    seedSingleProfileIdentity();
    var deviceA = objectMapper.readTree(loginResponseBody());
    var deviceB = objectMapper.readTree(loginResponseBody());

    changePassword(deviceA.get("accessToken").asString(), PASSWORD, "a brand new passphrase!")
        .andExpect(status().isOk());

    // Live session authorization revokes both API and refresh authority immediately.
    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + deviceB.get("accessToken").asString()))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(deviceB.get("refreshToken").asString())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
  }

  @Test
  @DisplayName("Should replace caller session with fresh tokens when password changed")
  void shouldReplaceCallerSessionWithFreshTokensWhenPasswordChanged() throws Exception {
    seedSingleProfileIdentity();
    var login = objectMapper.readTree(loginResponseBody());
    var oldAccessToken = login.get("accessToken").asString();
    var oldRefreshToken = login.get("refreshToken").asString();

    var changed =
        objectMapper.readTree(
            changePassword(oldAccessToken, PASSWORD, "a brand new passphrase!")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.scope").value("account"))
                .andReturn()
                .getResponse()
                .getContentAsString());

    // The replacement credentials work, while both forms of authority from the old session are
    // dead immediately.
    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + changed.get("accessToken").asString()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + oldAccessToken))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(changed.get("refreshToken").asString())))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(oldRefreshToken)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName(
      "Should keep password-change tokens in cookies when the request is cookie authenticated")
  void shouldKeepPasswordChangeTokensInCookiesWhenRequestIsCookieAuthenticated() throws Exception {
    seedSingleProfileIdentity();
    var loginResponse = cookieModeLogin();
    var accessCookie = loginResponse.getCookie("streamarr_access");
    var csrfCookie = loginResponse.getCookie(AuthCookies.CSRF_COOKIE);

    var response =
        mockMvc
            .perform(
                post("/api/auth/change-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .cookie(accessCookie, csrfCookie)
                    .header("X-XSRF-TOKEN", csrfCookie.getValue())
                    .content(
                        """
                        {"currentPassword": "%s", "newPassword": "%s", "cookieMode": false}
                        """
                            .formatted(PASSWORD, "a brand new passphrase!")))
            .andExpect(status().isOk())
            .andExpect(cookie().exists("streamarr_access"))
            .andExpect(cookie().exists("streamarr_refresh"))
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andReturn()
            .getResponse();

    assertUncacheable(response);
  }

  @Test
  @DisplayName("Should reject refresh when no token in body or cookie")
  void shouldRejectRefreshWhenNoTokenInBodyOrCookie() throws Exception {
    mockMvc
        .perform(post("/api/auth/refresh"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cookieMode\": true}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody("")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
  }

  @Test
  @DisplayName("Should reject password change when current password wrong")
  void shouldRejectPasswordChangeWhenCurrentPasswordWrong() throws Exception {
    seedSingleProfileIdentity();
    var accessToken = loginAndReadField("accessToken");

    changePassword(accessToken, "not the current password", "irrelevant new one")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

    // Nothing was revoked: the caller's token still authenticates.
    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should reject authenticated auth mutations when no identity is present")
  void shouldRejectAuthenticatedAuthMutationsWhenNoIdentityPresent() throws Exception {
    var passwordMarker = UUID.randomUUID().toString();

    mockMvc
        .perform(post("/api/auth/logout"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    mockMvc
        .perform(
            post("/api/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"currentPassword": "%s", "newPassword": "%s", "cookieMode": false}
                    """
                        .formatted(passwordMarker, UUID.randomUUID())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"profileId\": \"%s\", \"cookieMode\": false}".formatted(UUID.randomUUID())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("Should report setup completion state on status")
  void shouldReportSetupCompletionStateOnStatus() throws Exception {
    mockMvc
        .perform(get("/api/auth/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.setupComplete").value(false));

    seedSingleProfileIdentity();
    serverBootstrapRepository.claim(account.getId());

    mockMvc
        .perform(get("/api/auth/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.setupComplete").value(true));
  }

  @Test
  @DisplayName("Should revoke session and clear cookies when logging out")
  void shouldRevokeSessionAndClearCookiesWhenLoggingOut() throws Exception {
    seedSingleProfileIdentity();
    var login = objectMapper.readTree(loginResponseBody());
    var accessToken = login.get("accessToken").asString();

    var logoutResponse =
        mockMvc
            .perform(post("/api/auth/logout").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isNoContent())
            .andReturn()
            .getResponse();
    assertThat(logoutResponse.getCookie("streamarr_access").getMaxAge()).isZero();
    assertThat(logoutResponse.getCookie("streamarr_refresh").getMaxAge()).isZero();

    // Logout revokes API, refresh, and playback authority immediately.
    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(login.get("refreshToken").asString())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Should refuse caching when login and refresh return tokens")
  void shouldRefuseCachingWhenLoginAndRefreshReturnTokens() throws Exception {
    seedSingleProfileIdentity();

    var login =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(account.getEmail(), PASSWORD)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertUncacheable(login);

    var refreshToken = objectMapper.readTree(login.getContentAsString()).get("refreshToken");
    var refresh =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(refreshBody(refreshToken.asString())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertUncacheable(refresh);
  }

  @Test
  @DisplayName("Should refuse caching when setup returns tokens")
  void shouldRefuseCachingWhenSetupReturnsTokens() throws Exception {
    var suffix = UUID.randomUUID();
    setupEmail = "no-store-" + suffix + "@example.com";
    setupHouseholdName = "Home-" + suffix;

    var response =
        mockMvc
            .perform(
                post("/api/auth/setup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(setupBody(setupEmail)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();

    assertUncacheable(response);
  }

  @Test
  @DisplayName("Should refuse caching when selection returns derived tokens")
  void shouldRefuseCachingWhenSelectionReturnsDerivedTokens() throws Exception {
    var accountToken = accountScopedTokenWithTwoProfiles();

    var profileSelection =
        mockMvc
            .perform(
                post("/api/auth/select-profile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accountToken)
                    .content(
                        "{\"profileId\": \"%s\", \"cookieMode\": false}"
                            .formatted(profile.getId())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertUncacheable(profileSelection);
  }

  @Test
  @DisplayName("Should refuse caching when password change returns tokens")
  void shouldRefuseCachingWhenPasswordChangeReturnsTokens() throws Exception {
    seedSingleProfileIdentity();
    var accessToken = loginAndReadField("accessToken");

    var response =
        changePassword(accessToken, PASSWORD, "a brand new passphrase!")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    assertUncacheable(response);
  }

  @Test
  @DisplayName("Should refuse caching when an auth request is rejected")
  void shouldRefuseCachingWhenAuthRequestRejected() throws Exception {
    var unthrottledSource =
        "10.98."
            + ThreadLocalRandom.current().nextInt(250)
            + "."
            + ThreadLocalRandom.current().nextInt(250);

    var response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody("absent-" + UUID.randomUUID() + "@example.com", PASSWORD))
                    .with(
                        request -> {
                          request.setRemoteAddr(unthrottledSource);
                          return request;
                        }))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
            .andReturn()
            .getResponse();

    assertUncacheable(response);
  }

  private org.springframework.test.web.servlet.ResultActions changePassword(
      String bearerToken, String currentPassword, String newPassword) throws Exception {
    return mockMvc.perform(
        post("/api/auth/change-password")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + bearerToken)
            .content(
                """
                {"currentPassword": "%s", "newPassword": "%s", "cookieMode": false}
                """
                    .formatted(currentPassword, newPassword)));
  }

  private String loginResponseBody() throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(account.getEmail(), PASSWORD)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private org.springframework.mock.web.MockHttpServletResponse cookieModeLogin() throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email": "%s", "password": "%s", "deviceName": "it-device", \
                    "cookieMode": true}
                    """
                        .formatted(account.getEmail(), PASSWORD)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse();
  }

  private String loginAndReadField(String field) throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(account.getEmail(), PASSWORD)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).get(field).asString();
  }

  private org.springframework.security.oauth2.jwt.Jwt decodeToken(String token) {
    return jwtDecoder.decode(token);
  }

  private String signedAccessToken(
      AuthSession session, Consumer<JwtClaimsSet.Builder> customizeClaims) {
    var now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer("streamarr")
            .audience(List.of("streamarr"))
            .subject(account.getId().toString())
            .issuedAt(now)
            .expiresAt(now.plus(Duration.ofMinutes(10)))
            .id(UUID.randomUUID().toString())
            .claim(TokenClaims.ROLES, List.of(account.getAccountRole().name()))
            .claim(TokenClaims.SESSION_ID, session.getId().toString())
            .claim(TokenClaims.SCOPE, "account");
    customizeClaims.accept(claims);

    return jwtEncoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.ES256).build(), claims.build()))
        .getTokenValue();
  }

  /** Minted against the real identity graph with a fixed past clock — expired but well-formed. */
  private String expiredAccessToken() {
    var issued = refreshTokenService.createSession(account, "expired-token-test");
    var cryptoConfig = new TokenCryptoConfig();
    var pastClock = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
    var pastIssuer =
        new AccessTokenIssuer(
            cryptoConfig.jwtEncoder(cryptoConfig.tokenSigningKeys(tokenProperties)),
            tokenProperties,
            pastClock);

    return pastIssuer
        .issue(
            TokenContext.builder()
                .account(account)
                .session(issued.session())
                .profileId(profile.getId())
                .build())
        .value();
  }

  private String loginBody(String email, String password) {
    return """
        {"email": "%s", "password": "%s", "deviceName": "it-device", "cookieMode": false}
        """
        .formatted(email, password);
  }

  private String refreshBody(String refreshToken) {
    return """
        {"refreshToken": "%s", "cookieMode": false}
        """
        .formatted(refreshToken);
  }

  private String setupBody(String email) {
    return """
        {"email": "%s", "displayName": "Admin", "password": "%s", \
        "householdName": "%s", "profileName": "Andrew", "cookieMode": false}
        """
        .formatted(email, PASSWORD, setupHouseholdName);
  }

  private String loginAndReturnRefreshToken() throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(account.getEmail(), PASSWORD)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).get("refreshToken").asString();
  }

  private String redeemAndReturnRefreshToken(String refreshToken) throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(refreshBody(refreshToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andExpect(jsonPath("$.scope").value("account"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).get("refreshToken").asString();
  }

  private void seedSingleProfileIdentity() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              household =
                  householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
              account =
                  userAccountRepository.save(
                      AccountFixture.defaultAccountBuilder()
                          .passwordHash(passwordEncoder.encode(PASSWORD))
                          .homeHouseholdId(household.getId())
                          .householdRole(HouseholdRole.OWNER)
                          .build());
              profile = createPortableProfile();
            });
  }

  private Profile createPortableProfile() {
    return new TransactionTemplate(transactionManager).execute(_ -> persistPortableProfile());
  }

  private Profile persistPortableProfile() {
    var portableProfile = profileRepository.save(ProfileFixture.defaultProfileBuilder().build());
    profileManagerRepository.save(
        ProfileManager.builder()
            .accountId(account.getId())
            .profileId(portableProfile.getId())
            .build());
    profileShareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(portableProfile.getId())
            .householdId(household.getId())
            .status(ProfileShareStatus.ACTIVE)
            .build());
    return portableProfile;
  }

  private void deleteIdentityByEmail(String email) {
    if (email == null) {
      return;
    }
    var accountId =
        dsl.select(USER_ACCOUNT.ID)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.EMAIL.eq(email))
            .fetchOne(USER_ACCOUNT.ID);
    if (accountId != null) {
      deleteIdentityByAccountId(accountId);
    }
  }

  private void deleteIdentityByAccountId(UUID accountId) {
    var householdId =
        dsl.select(USER_ACCOUNT.HOME_HOUSEHOLD_ID)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.eq(accountId))
            .fetchOne(USER_ACCOUNT.HOME_HOUSEHOLD_ID);
    var profileIds =
        dsl.select(PROFILE_MANAGER.PROFILE_ID)
            .from(PROFILE_MANAGER)
            .where(PROFILE_MANAGER.ACCOUNT_ID.eq(accountId))
            .fetch(PROFILE_MANAGER.PROFILE_ID);
    dsl.deleteFrom(PROFILE_HOUSEHOLD_SHARE)
        .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.in(profileIds))
        .execute();
    dsl.deleteFrom(PROFILE_MANAGER_INVITATION)
        .where(PROFILE_MANAGER_INVITATION.PROFILE_ID.in(profileIds))
        .execute();
    profileIds.forEach(
        profileId ->
            deletionAuthorizationRepository.saveAndFlush(
                ProfileDeletionAuthorization.builder()
                    .profileId(profileId)
                    .actingAccountId(accountId)
                    .mode(ProfileDeletionMode.ORDINARY)
                    .build()));
    dsl.deleteFrom(PROFILE).where(PROFILE.ID.in(profileIds)).execute();
    dsl.deleteFrom(USER_ACCOUNT).where(USER_ACCOUNT.ID.eq(accountId)).execute();
    if (householdId != null) {
      dsl.deleteFrom(HOUSEHOLD)
          .where(HOUSEHOLD.ID.eq(householdId))
          .andNotExists(
              dsl.selectOne()
                  .from(USER_ACCOUNT)
                  .where(USER_ACCOUNT.HOME_HOUSEHOLD_ID.eq(householdId)))
          .execute();
    }
  }

  private static String sameSiteOf(Cookie cookie) {
    return cookie.getAttribute("SameSite");
  }

  /**
   * RFC 6749 §5.1 requires Cache-Control: no-store and Pragma: no-cache on every response carrying
   * tokens. Both arrive from Spring Security's CacheControlHeadersWriter, which writes
   * Cache-Control, Pragma and Expires as one group and skips all three the moment a handler has
   * already set any of them. An explicit cacheControl(...) on an auth response therefore drops
   * Pragma rather than adding to it — that is the regression these assertions catch.
   */
  private static void assertUncacheable(MockHttpServletResponse response) {
    assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
  }
}
