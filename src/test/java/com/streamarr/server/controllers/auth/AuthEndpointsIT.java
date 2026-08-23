package com.streamarr.server.controllers.auth;

import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
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
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.ServerBootstrapRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.auth.TokenClaims;
import com.streamarr.server.services.auth.TokenContext;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupportConfig;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@DisplayName("Auth Endpoints Integration Tests")
@Import(AuthTestSupportConfig.class)
class AuthEndpointsIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private ProfileRepository profileRepository;

  @Autowired private ProfileHouseholdShareRepository shareRepository;

  @Autowired private ProfileManagerRepository profileManagerRepository;

  @Autowired private PasswordEncoder passwordEncoder;

  @Autowired private DSLContext dsl;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private AuthTokenProperties tokenProperties;

  @Autowired private RefreshTokenService refreshTokenService;

  @Autowired private JwtEncoder jwtEncoder;
  @Autowired private JwtDecoder jwtDecoder;

  @Autowired private ServerBootstrapRepository serverBootstrapRepository;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private TransactionTemplate transactionTemplate;

  private static final String SETUP_PASSWORD = UUID.randomUUID().toString();

  private String password;
  private UserAccount account;
  private Household household;
  private Profile profile;
  private AuthTestSupport.TestIdentity identity;
  private AuthTestSupport.TestIdentity host;
  private String setupEmail;

  @AfterEach
  void deleteIdentityGraph() {
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();
    if (setupEmail != null) {
      userAccountRepository
          .findByEmailIgnoreCase(setupEmail)
          .ifPresent(created -> authTestSupport.deleteAccount(created.getId()));
    }
    if (host != null) {
      authTestSupport.deleteIdentity(host);
    }
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
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
                            .formatted(account.getEmail(), password)))
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
                .content(loginBody(account.getEmail(), password))
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
  @DisplayName("Should accept an issued profile token until expiry when the Profile is unshared")
  void shouldAcceptIssuedProfileTokenUntilExpiryWhenProfileIsUnshared() throws Exception {
    seedSingleProfileIdentity();
    var managed = seedManagedProfile();
    var accessToken = selectProfileToken(loginAndReadField("accessToken"), managed.getId());

    // Control: the fresh profile token authenticates (probe passes the filter, then 404s).
    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isNotFound());

    endShare(managed.getId(), household.getId());

    // Authorization changes take effect on refresh; an issued API token keeps its bounded TTL.
    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isNotFound());
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
                            .formatted(account.getEmail(), password)))
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
    selectProfileToken(login.get("accessToken").asString(), profile.getId());

    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(login.get("refreshToken").asString())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scope").value("profile"));
  }

  @Test
  @DisplayName("Should return to the Profile picker when refresh finds the selection locked")
  void shouldReturnToProfilePickerWhenRefreshFindsSelectionLocked() throws Exception {
    seedSingleProfileIdentity();
    var login = objectMapper.readTree(loginResponseBody());
    selectProfileToken(login.get("accessToken").asString(), profile.getId());
    seedManagedKidProfile();

    // Adding the Kid locks the selected unpinned Adult, so refresh returns to the picker.
    var refreshed =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(refreshBody(login.get("refreshToken").asString())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("account"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    var claims = decodeToken(objectMapper.readTree(refreshed).get("accessToken").asString());
    assertThat(claims.hasClaim(TokenClaims.PROFILE_ID)).isFalse();
  }

  @Test
  @DisplayName("Should fall back to the membership Household picker when Household access is lost")
  void shouldFallBackToMembershipHouseholdPickerWhenHouseholdAccessIsLost() throws Exception {
    seedSingleProfileIdentity();
    var visited = seedVisitedHousehold();
    var login = objectMapper.readTree(loginResponseBody());
    var visitingToken =
        selectHouseholdToken(login.get("accessToken").asString(), visited.household().getId());
    selectProfileToken(visitingToken, profile.getId());

    // Ending the visitor's share: refresh must never trust the stored context without
    // revalidating it.
    endShare(profile.getId(), visited.household().getId());

    var refreshed =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(refreshBody(login.get("refreshToken").asString())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("account"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var claims = decodeToken(objectMapper.readTree(refreshed).get("accessToken").asString());
    assertThat(claims.getClaimAsString("ch")).isEqualTo(household.getId().toString());
    assertThat(claims.hasClaim("pf")).isFalse();
  }

  @Test
  @DisplayName("Should clear the selected Profile when the Household is switched")
  void shouldClearSelectedProfileWhenHouseholdIsSwitched() throws Exception {
    seedSingleProfileIdentity();
    var visited = seedVisitedHousehold();

    // Login stays at the picker of the membership Household.
    var loginResponse = loginResponseBody();
    var accessToken = objectMapper.readTree(loginResponse).get("accessToken").asString();
    assertThat(objectMapper.readTree(loginResponse).get("scope").asString()).isEqualTo("account");

    var profileScopedToken = selectProfileToken(accessToken, profile.getId());
    var profileClaims = decodeToken(profileScopedToken);
    assertThat(profileClaims.getClaimAsString("ch")).isEqualTo(household.getId().toString());
    assertThat(profileClaims.getClaimAsString("pf")).isEqualTo(profile.getId().toString());

    // Switching to the visited Household clears the Profile — never a mismatched ch/pf pair.
    var switched = selectHouseholdToken(profileScopedToken, visited.household().getId());
    var switchedClaims = decodeToken(switched);
    assertThat(switchedClaims.getClaimAsString("scope")).isEqualTo("account");
    assertThat(switchedClaims.getClaimAsString("ch"))
        .isEqualTo(visited.household().getId().toString());
    assertThat(switchedClaims.getClaimAsString("hh")).isEqualTo(household.getId().toString());
    assertThat(switchedClaims.hasClaim("pf")).isFalse();
  }

  @Test
  @DisplayName("Should reject Profile selection when the Household context is superseded")
  void shouldRejectProfileSelectionWhenHouseholdContextIsSuperseded() throws Exception {
    var staleMembershipToken = accountScopedTokenWithTwoProfiles();
    var managed = seedManagedProfile();
    var visited = seedVisitedHousehold();
    selectHouseholdToken(staleMembershipToken, visited.household().getId());

    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + staleMembershipToken)
                .content(
                    "{\"profileId\": \"%s\", \"cookieMode\": false}".formatted(managed.getId())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PROFILE_ACCESS_DENIED"));
  }

  @Test
  @DisplayName("Should deny switching when the Account may not use the Household")
  void shouldDenySwitchingWhenAccountMayNotUseHousehold() throws Exception {
    seedSingleProfileIdentity();
    host = authTestSupport.createIdentity();
    var accessToken = loginAndReadField("accessToken");

    mockMvc
        .perform(
            post("/api/auth/select-household")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(
                    "{\"householdId\": \"%s\", \"cookieMode\": false}"
                        .formatted(host.household().getId())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("HOUSEHOLD_ACCESS_DENIED"));
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
    assertThat(claims.getClaimAsString("ch")).isEqualTo(household.getId().toString());
  }

  @Test
  @DisplayName("Should require the PIN when selecting a Profile that has one")
  void shouldRequirePinWhenSelectingProfileThatHasOne() throws Exception {
    var accountToken = accountScopedTokenWithTwoProfiles();
    var pinned = profileRepository.findById(profile.getId()).orElseThrow();
    pinned.setPinHash(passwordEncoder.encode("4242"));
    profileRepository.saveAndFlush(pinned);

    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accountToken)
                .content(
                    "{\"profileId\": \"%s\", \"cookieMode\": false}".formatted(profile.getId())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_PROFILE_PIN"));

    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accountToken)
                .content(
                    "{\"profileId\": \"%s\", \"pin\": \"0000\", \"cookieMode\": false}"
                        .formatted(profile.getId())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_PROFILE_PIN"));

    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accountToken)
                .content(
                    "{\"profileId\": \"%s\", \"pin\": \"4242\", \"cookieMode\": false}"
                        .formatted(profile.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scope").value("profile"));
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
    seedManagedProfile();
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
    var householdToken = accountScopedTokenWithTwoProfiles();

    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + householdToken)
                .content("{\"cookieMode\": false}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Should reject profile selection when profile not linked")
  void shouldRejectProfileSelectionWhenProfileNotLinked() throws Exception {
    var householdToken = accountScopedTokenWithTwoProfiles();

    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + householdToken)
                .content(
                    "{\"profileId\": \"%s\", \"cookieMode\": false}".formatted(UUID.randomUUID())))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should not advance expiry when selection repeated")
  void shouldNotAdvanceExpiryWhenSelectionRepeated() throws Exception {
    accountScopedTokenWithTwoProfiles();
    var sourceExpiry = Instant.now().plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS);
    var sourceToken =
        signedAccessToken(identity.session(), claims -> claims.expiresAt(sourceExpiry));

    var firstToken = selectProfileToken(sourceToken, profile.getId());
    var firstExpiry = decodeToken(firstToken).getExpiresAt();

    var secondToken = selectProfileToken(firstToken, profile.getId());
    var secondExpiry = decodeToken(secondToken).getExpiresAt();

    assertThat(firstExpiry).isEqualTo(sourceExpiry);
    assertThat(secondExpiry).isEqualTo(firstExpiry);
  }

  private String selectHouseholdToken(String bearerToken, UUID householdId) throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/auth/select-household")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + bearerToken)
                    .content(
                        "{\"householdId\": \"%s\", \"cookieMode\": false}".formatted(householdId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).get("accessToken").asString();
  }

  private String selectProfileToken(String bearerToken, UUID profileId) throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/auth/select-profile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + bearerToken)
                    .content("{\"profileId\": \"%s\", \"cookieMode\": false}".formatted(profileId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).get("accessToken").asString();
  }

  /** A Household with two available Profiles: login lands at the picker (Account scope). */
  private String accountScopedTokenWithTwoProfiles() throws Exception {
    seedSingleProfileIdentity();
    seedManagedProfile();

    var response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(account.getEmail(), password)))
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
            post("/api/auth/select-household")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(accessCookie)
                .content(
                    "{\"householdId\": \"%s\", \"cookieMode\": true}".formatted(household.getId())))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should keep household selection in the cookie response when cookie authenticated")
  void shouldKeepHouseholdSelectionInCookieResponseWhenCookieAuthenticated() throws Exception {
    seedSingleProfileIdentity();
    var loginResponse = cookieModeLogin();
    var accessCookie = loginResponse.getCookie("streamarr_access");
    var csrfCookie = loginResponse.getCookie(AuthCookies.CSRF_COOKIE);

    mockMvc
        .perform(
            post("/api/auth/select-household")
                .contentType(MediaType.APPLICATION_JSON)
                .cookie(accessCookie, csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue())
                .content(
                    "{\"householdId\": \"%s\", \"cookieMode\": false}"
                        .formatted(household.getId())))
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

    changePassword(deviceA.get("accessToken").asString(), password, "a brand new passphrase!")
        .andExpect(status().isOk());

    // Short-lived API access remains valid until expiry; refresh authority ends immediately.
    assertStillAuthenticates(deviceB.get("accessToken").asString());
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
            changePassword(oldAccessToken, password, "a brand new passphrase!")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.scope").value("account"))
                .andReturn()
                .getResponse()
                .getContentAsString());

    // The replacement credentials work. The old refresh authority is dead, while the short-lived
    // API token remains valid until its strict expiry.
    assertStillAuthenticates(changed.get("accessToken").asString());
    assertStillAuthenticates(oldAccessToken);
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
                            .formatted(password, "a brand new passphrase!")))
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
    assertStillAuthenticates(accessToken);
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
            post("/api/auth/select-household")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"householdId\": \"%s\", \"cookieMode\": false}"
                        .formatted(UUID.randomUUID())))
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
  @DisplayName("Should report setup completion state when status is requested")
  void shouldReportSetupCompletionStateWhenStatusRequested() throws Exception {
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
  @DisplayName("Should not expose legacy logout endpoint when authenticated")
  void shouldNotExposeLegacyLogoutEndpointWhenAuthenticated() throws Exception {
    seedSingleProfileIdentity();
    var accessToken = loginAndReadField("accessToken");

    mockMvc
        .perform(post("/api/auth/logout").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should revoke only presented refresh family when logging out")
  void shouldRevokeOnlyPresentedRefreshFamilyWhenLoggingOut() throws Exception {
    seedSingleProfileIdentity();
    var loggedOutDevice = objectMapper.readTree(loginResponseBody());
    var otherDevice = objectMapper.readTree(loginResponseBody());

    mockMvc
        .perform(
            post("/api/auth/refresh/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(loggedOutDevice.get("refreshToken").asString())))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(loggedOutDevice.get("refreshToken").asString())))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(otherDevice.get("refreshToken").asString())))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should keep existing access token usable when logging out")
  void shouldKeepExistingAccessTokenUsableWhenLoggingOut() throws Exception {
    seedSingleProfileIdentity();
    var login = objectMapper.readTree(loginResponseBody());

    mockMvc
        .perform(
            post("/api/auth/refresh/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(login.get("refreshToken").asString())))
        .andExpect(status().isNoContent());

    assertStillAuthenticates(login.get("accessToken").asString());
  }

  @Test
  @DisplayName("Should revoke refresh family when browser csrf token present")
  void shouldRevokeRefreshFamilyWhenBrowserCsrfTokenPresent() throws Exception {
    seedSingleProfileIdentity();
    var loginResponse = cookieModeLogin();
    var refreshCookie = loginResponse.getCookie(AuthCookies.REFRESH_COOKIE);
    var csrfCookie = loginResponse.getCookie(AuthCookies.CSRF_COOKIE);

    mockMvc
        .perform(
            post("/api/auth/refresh/revoke")
                .cookie(refreshCookie, csrfCookie)
                .header(AuthCookies.CSRF_HEADER, csrfCookie.getValue()))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(refreshCookie.getValue())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Should clear auth cookies when browser logout succeeds")
  void shouldClearAuthCookiesWhenBrowserLogoutSucceeds() throws Exception {
    seedSingleProfileIdentity();
    var loginResponse = cookieModeLogin();
    var refreshCookie = loginResponse.getCookie(AuthCookies.REFRESH_COOKIE);
    var csrfCookie = loginResponse.getCookie(AuthCookies.CSRF_COOKIE);

    var logoutResponse =
        mockMvc
            .perform(
                post("/api/auth/refresh/revoke")
                    .cookie(refreshCookie, csrfCookie)
                    .header(AuthCookies.CSRF_HEADER, csrfCookie.getValue()))
            .andExpect(status().isNoContent())
            .andReturn()
            .getResponse();

    assertThat(logoutResponse.getCookie(AuthCookies.ACCESS_COOKIE).getMaxAge()).isZero();
    assertThat(logoutResponse.getCookie(AuthCookies.REFRESH_COOKIE).getMaxAge()).isZero();
  }

  @Test
  @DisplayName("Should reject browser logout and keep refresh family live when csrf token missing")
  void shouldRejectBrowserLogoutAndKeepRefreshFamilyLiveWhenCsrfTokenMissing() throws Exception {
    seedSingleProfileIdentity();
    var refreshCookie = cookieModeLogin().getCookie(AuthCookies.REFRESH_COOKIE);

    mockMvc
        .perform(post("/api/auth/refresh/revoke").cookie(refreshCookie))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_REQUIRED"));
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(refreshCookie.getValue())))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName(
      "Should reject browser logout and keep refresh family live when csrf token mismatched")
  void shouldRejectBrowserLogoutAndKeepRefreshFamilyLiveWhenCsrfTokenMismatched() throws Exception {
    seedSingleProfileIdentity();
    var loginResponse = cookieModeLogin();
    var refreshCookie = loginResponse.getCookie(AuthCookies.REFRESH_COOKIE);
    var csrfCookie = loginResponse.getCookie(AuthCookies.CSRF_COOKIE);

    mockMvc
        .perform(
            post("/api/auth/refresh/revoke")
                .cookie(refreshCookie, csrfCookie)
                .header(AuthCookies.CSRF_HEADER, "mismatched-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_REQUIRED"));
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(refreshCookie.getValue())))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should keep other refresh family live when logout repeated")
  void shouldKeepOtherRefreshFamilyLiveWhenLogoutRepeated() throws Exception {
    seedSingleProfileIdentity();
    var refreshToken = loginAndReturnRefreshToken();
    var otherRefreshToken = loginAndReturnRefreshToken();

    mockMvc
        .perform(
            post("/api/auth/refresh/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(refreshToken)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            post("/api/auth/refresh/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(refreshToken)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(otherRefreshToken)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should keep existing refresh family live when logout token unknown")
  void shouldKeepExistingRefreshFamilyLiveWhenLogoutTokenUnknown() throws Exception {
    seedSingleProfileIdentity();
    var existingRefreshToken = loginAndReturnRefreshToken();

    mockMvc
        .perform(
            post("/api/auth/refresh/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody("never-issued-token")))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(existingRefreshToken)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should reject logout when refresh credential missing")
  void shouldRejectLogoutWhenRefreshCredentialMissing() throws Exception {
    mockMvc
        .perform(post("/api/auth/refresh/revoke"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
  }

  @Test
  @DisplayName("Should reject logout when refresh credential blank")
  void shouldRejectLogoutWhenRefreshCredentialBlank() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/refresh/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody("")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
  }

  @Test
  @DisplayName("Should revoke family when rotated predecessor presented")
  void shouldRevokeFamilyWhenRotatedPredecessorPresented() throws Exception {
    seedSingleProfileIdentity();
    var predecessor = loginAndReturnRefreshToken();
    var successor = redeemAndReturnRefreshToken(predecessor);

    mockMvc
        .perform(
            post("/api/auth/refresh/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(predecessor)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(successor)))
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
                    .content(loginBody(account.getEmail(), password)))
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

    var householdSelection =
        mockMvc
            .perform(
                post("/api/auth/select-household")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accountToken)
                    .content(
                        "{\"householdId\": \"%s\", \"cookieMode\": false}"
                            .formatted(household.getId())))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertUncacheable(householdSelection);

    var derivedToken =
        objectMapper.readTree(householdSelection.getContentAsString()).get("accessToken");
    var profileSelection =
        mockMvc
            .perform(
                post("/api/auth/select-profile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + derivedToken.asString())
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
        changePassword(accessToken, password, "a brand new passphrase!")
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
                    .content(
                        loginBody("absent-" + UUID.randomUUID() + "@example.com", SETUP_PASSWORD))
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

  private ResultActions changePassword(
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
                .content(loginBody(account.getEmail(), password)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private MockHttpServletResponse cookieModeLogin() throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email": "%s", "password": "%s", "deviceName": "it-device", \
                    "cookieMode": true}
                    """
                        .formatted(account.getEmail(), password)))
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
                    .content(loginBody(account.getEmail(), password)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).get(field).asString();
  }

  private Jwt decodeToken(String token) {
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
            .claim(TokenClaims.SESSION_ID, session.getId().toString())
            .claim(TokenClaims.SCOPE, "account")
            .claim(TokenClaims.HOUSEHOLD_ID, account.getHouseholdId().toString())
            .claim(TokenClaims.HOUSEHOLD_ROLE, account.getHouseholdRole().name())
            .claim(TokenClaims.CONTEXT_HOUSEHOLD_ID, account.getHouseholdId().toString());
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
                .contextHouseholdId(household.getId())
                .profileId(Optional.of(profile.getId()))
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
        "householdName": "Home", "profileName": "Andrew", "cookieMode": false}
        """
        .formatted(email, SETUP_PASSWORD);
  }

  private String loginAndReturnRefreshToken() throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(account.getEmail(), password)))
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

  /** One Household, one HouseholdAdmin Account with its unrestricted Adult Personal Profile. */
  private void seedSingleProfileIdentity() {
    identity = authTestSupport.createIdentity();
    password = authTestSupport.password();
    account = identity.account();
    household = identity.household();
    profile = identity.profile();
  }

  /**
   * A second, unlinked Profile the Account manages, available in its Household. One transaction:
   * the deferred home-anchor trigger checks the whole shape at commit.
   */
  private Profile seedManagedProfile() {
    return transactionTemplate.execute(
        _ -> {
          var managed =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(account.getId())
                  .profileId(managed.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(managed.getId())
                  .householdId(household.getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return managed;
        });
  }

  private Profile seedManagedKidProfile() {
    return transactionTemplate.execute(
        _ -> {
          var kid =
              profileRepository.saveAndFlush(
                  ProfileFixture.kidProfileBuilder().householdId(household.getId()).build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder().accountId(account.getId()).profileId(kid.getId()).build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(kid.getId())
                  .householdId(household.getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return kid;
        });
  }

  /** Another Household the Account may use as a visitor through its Personal Profile's share. */
  private AuthTestSupport.TestIdentity seedVisitedHousehold() {
    host = authTestSupport.createIdentity();
    shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(host.household().getId())
            .status(ProfileShareStatus.ACTIVE)
            .build());
    return host;
  }

  private void endShare(UUID profileId, UUID householdId) {
    var share =
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                profileId, householdId, ProfileShareStatus.ACTIVE)
            .orElseThrow();
    share.setStatus(ProfileShareStatus.ENDED);
    share.setEndedAt(Instant.now());
    shareRepository.saveAndFlush(share);
  }

  /** Proves the token still authenticates: GraphQL accepts ACCOUNT scope and answers 200. */
  private void assertStillAuthenticates(String accessToken) throws Exception {
    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content("{\"query\": \"{ __typename }\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.__typename").value("Query"));
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
