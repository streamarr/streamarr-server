package com.streamarr.server.controllers.auth;

import static com.streamarr.server.jooq.generated.tables.Household.HOUSEHOLD;
import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.auth.AccountProfile;
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
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.auth.TokenContext;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@DisplayName("Auth Endpoints Integration Tests")
class AuthEndpointsIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct horse battery staple";

  @Autowired private MockMvc mockMvc;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private HouseholdMembershipRepository membershipRepository;

  @Autowired private ProfileRepository profileRepository;

  @Autowired private AccountProfileRepository accountProfileRepository;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private DSLContext dsl;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private AuthTokenProperties tokenProperties;

  @Autowired private RefreshTokenService refreshTokenService;

  private UserAccount account;
  private Household household;
  private Profile profile;
  private UUID secondHouseholdId;
  private String setupEmail;
  private String setupHouseholdName;

  @AfterEach
  void deleteIdentityGraph() {
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();
    if (setupHouseholdName != null) {
      dsl.deleteFrom(HOUSEHOLD).where(HOUSEHOLD.NAME.eq(setupHouseholdName)).execute();
    }
    if (setupEmail != null) {
      dsl.deleteFrom(USER_ACCOUNT).where(USER_ACCOUNT.EMAIL.eq(setupEmail)).execute();
    }
    if (secondHouseholdId != null) {
      householdRepository.deleteById(secondHouseholdId);
    }
    if (household != null) {
      householdRepository.deleteById(household.getId());
    }
    if (account != null) {
      userAccountRepository.deleteById(account.getId());
    }
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
            .andExpect(jsonPath("$.scope").value("profile"))
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

    var refreshCookie = response.getCookie("streamarr_refresh");
    assertThat(refreshCookie).isNotNull();
    assertThat(refreshCookie.getPath()).isEqualTo("/api/auth/refresh");
    assertThat(refreshCookie.isHttpOnly()).isTrue();
    assertThat(refreshCookie.getSecure()).isTrue();
    assertThat(sameSiteOf(refreshCookie)).isEqualTo("Strict");
    assertThat(refreshCookie.getValue()).isNotBlank();
  }

  @Test
  @DisplayName("Should create identity on first setup and conflict on second")
  void shouldCreateIdentityOnFirstSetupAndConflictOnSecond() throws Exception {
    var suffix = UUID.randomUUID();
    setupEmail = "setup-" + suffix + "@example.com";
    setupHouseholdName = "Home-" + suffix;
    var setupBody =
        """
        {"email": "%s", "displayName": "Admin", "password": "%s", \
        "householdName": "%s", "profileName": "Andrew", "cookieMode": false}
        """
            .formatted(setupEmail, PASSWORD, setupHouseholdName);

    mockMvc
        .perform(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON).content(setupBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.refreshToken").isNotEmpty())
        .andExpect(jsonPath("$.accessTokenExpiresAt").exists())
        .andExpect(jsonPath("$.scope").value("profile"));

    var secondBody = setupBody.replace(setupEmail, "second-" + suffix + "@example.com");

    mockMvc
        .perform(
            post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON).content(secondBody))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("SETUP_ALREADY_COMPLETED"));
  }

  @Test
  @DisplayName("Should throttle login when failures exceed limit")
  void shouldThrottleLoginWhenFailuresExceedLimit() throws Exception {
    seedSingleProfileIdentity();
    var throttledSource =
        "10.99." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);

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
  @DisplayName("Should rotate refresh token over http and grace replay without rotation")
  void shouldRotateRefreshTokenOverHttpAndGraceReplayWithoutRotation() throws Exception {
    seedSingleProfileIdentity();

    var loginResponse =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(account.getEmail(), PASSWORD)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var firstRefreshToken = objectMapper.readTree(loginResponse).get("refreshToken").asString();

    var rotated =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(refreshBody(firstRefreshToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andExpect(jsonPath("$.scope").value("profile"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var rotatedRefreshToken = objectMapper.readTree(rotated).get("refreshToken").asString();
    assertThat(rotatedRefreshToken).isNotEqualTo(firstRefreshToken);

    // Replaying the consumed token inside the grace window yields access only — no rotation.
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(firstRefreshToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.refreshToken").doesNotExist());
  }

  @Test
  @DisplayName("Should reject existing profile token when profile link revoked")
  void shouldRejectExistingProfileTokenWhenProfileLinkRevoked() throws Exception {
    seedSingleProfileIdentity();
    var loginResponse =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(account.getEmail(), PASSWORD)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var accessToken = objectMapper.readTree(loginResponse).get("accessToken").asString();

    // Control: the fresh profile token authenticates (probe passes the filter, then 404s).
    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isNotFound());

    accountProfileRepository.revokeProfileLink(
        AccountProfile.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .profileId(profile.getId())
            .build());

    // The membership counter bump makes every outstanding profile token stale immediately.
    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
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
    var csrfCookie = loginResponse.getCookie("XSRF-TOKEN");

    // Browsers attach the Path=/ access cookie to every request — including refresh. An expired
    // access credential must never deadlock renewal into logout.
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .cookie(
                    new Cookie("streamarr_access", expiredAccessToken()), refreshCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessTokenExpiresAt").exists())
        .andExpect(jsonPath("$.scope").value("profile"))
        .andReturn()
        .getResponse()
        .getCookies();
  }

  @Test
  @DisplayName("Should preserve profile scope when refreshing")
  void shouldPreserveProfileScopeWhenRefreshing() throws Exception {
    seedSingleProfileIdentity();
    var refreshToken = loginAndReadField("refreshToken");

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
    var refreshToken = loginAndReadField("refreshToken");

    // Revoking the membership cascades the profile link away; refresh must never trust the
    // stored selection without revalidating it.
    var membership =
        membershipRepository
            .findByAccountIdAndHouseholdId(account.getId(), household.getId())
            .orElseThrow();
    membershipRepository.delete(membership);

    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(refreshToken)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scope").value("account"));
  }

  @Test
  @DisplayName("Should clear active profile when household switched")
  void shouldClearActiveProfileWhenHouseholdSwitched() throws Exception {
    seedSingleProfileIdentity();
    var secondHousehold =
        householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    secondHouseholdId = secondHousehold.getId();
    membershipRepository.save(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(secondHousehold.getId())
            .householdRole(HouseholdRole.MEMBER)
            .build());
    for (int i = 0; i < 2; i++) {
      var extraProfile =
          profileRepository.save(
              ProfileFixture.defaultProfileBuilder().householdId(secondHousehold.getId()).build());
      accountProfileRepository.save(
          AccountProfile.builder()
              .accountId(account.getId())
              .householdId(secondHousehold.getId())
              .profileId(extraProfile.getId())
              .build());
    }

    // Two households: login stays at account scope (no auto-selection).
    var loginResponse =
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
    var accessToken = objectMapper.readTree(loginResponse).get("accessToken").asString();

    // Selecting the single-profile household auto-selects its sole profile.
    var firstSelect =
        mockMvc
            .perform(
                post("/api/auth/select-household")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .content(
                        "{\"householdId\": \"%s\", \"cookieMode\": false}"
                            .formatted(household.getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("profile"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var profileScopedToken = objectMapper.readTree(firstSelect).get("accessToken").asString();
    var profileClaims = decodeToken(profileScopedToken);
    assertThat(profileClaims.getClaimAsString("hh")).isEqualTo(household.getId().toString());
    assertThat(profileClaims.getClaimAsString("pf")).isEqualTo(profile.getId().toString());

    // Switching to the two-profile household clears the profile — never a mismatched hh/pf pair.
    var secondSelect =
        mockMvc
            .perform(
                post("/api/auth/select-household")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + profileScopedToken)
                    .content(
                        "{\"householdId\": \"%s\", \"cookieMode\": false}"
                            .formatted(secondHousehold.getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("household"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var householdScopedToken = objectMapper.readTree(secondSelect).get("accessToken").asString();
    var householdClaims = decodeToken(householdScopedToken);
    assertThat(householdClaims.getClaimAsString("hh"))
        .isEqualTo(secondHousehold.getId().toString());
    assertThat(householdClaims.hasClaim("pf")).isFalse();
  }

  @Test
  @DisplayName("Should reject cookie authenticated post without csrf token")
  void shouldRejectCookieAuthenticatedPostWithoutCsrfToken() throws Exception {
    seedSingleProfileIdentity();
    var accessCookie = cookieModeLogin().getCookie("streamarr_access");

    mockMvc
        .perform(
            post("/api/auth/select-household")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(accessCookie)
                .content(
                    "{\"householdId\": \"%s\", \"cookieMode\": true}".formatted(household.getId())))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should accept bearer post without csrf token")
  void shouldAcceptBearerPostWithoutCsrfToken() throws Exception {
    seedSingleProfileIdentity();
    var accessToken = loginAndReadField("accessToken");

    mockMvc
        .perform(
            post("/api/auth/select-household")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(
                    "{\"householdId\": \"%s\", \"cookieMode\": false}"
                        .formatted(household.getId())))
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
    var csrfCookie = loginResponse.getCookie("XSRF-TOKEN");
    assertThat(csrfCookie).isNotNull();

    // The page reads the XSRF-TOKEN cookie and echoes its raw value — the SW contract.
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .cookie(refreshCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scope").value("profile"));
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
    var keyBytes = java.util.Base64.getDecoder().decode(tokenProperties.signingKey());
    return org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withSecretKey(
            new javax.crypto.spec.SecretKeySpec(keyBytes, "HmacSHA256"))
        .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
        .build()
        .decode(token);
  }

  /** Minted against the real identity graph with a fixed past clock — expired but well-formed. */
  private String expiredAccessToken() {
    var issued = refreshTokenService.createSession(account, "expired-token-test");
    var cryptoConfig = new TokenCryptoConfig();
    var pastClock = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
    var pastIssuer =
        new AccessTokenIssuer(
            cryptoConfig.jwtEncoder(cryptoConfig.authSigningKey(tokenProperties)),
            tokenProperties,
            pastClock,
            membershipRepository,
            profileRepository,
            accountProfileRepository);

    return pastIssuer
        .issue(
            TokenContext.builder()
                .account(account)
                .session(issued.session())
                .householdId(household.getId())
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

  private void seedSingleProfileIdentity() {
    account =
        userAccountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .build());
    household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    membershipRepository.save(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.OWNER)
            .build());
    profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    accountProfileRepository.save(
        AccountProfile.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .profileId(profile.getId())
            .build());
  }

  private static String sameSiteOf(Cookie cookie) {
    return cookie.getAttribute("SameSite");
  }
}
