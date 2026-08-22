package com.streamarr.server.config.security;

import static com.streamarr.server.support.AuthTestSupport.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import com.streamarr.server.support.AuthTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.test.web.servlet.MockMvc;

/** The permit/deny matrix as executable specification — changes here are contract changes. */
@Tag("IntegrationTest")
@DisplayName("Security Filter Chain Integration Tests")
class SecurityFilterChainIT extends AbstractIntegrationTest {

  private static final String ACCOUNT_QUERY = "{\"query\": \"{ me { accountId } }\"}";
  private static final String CSRF_HEADER = AuthCookies.CSRF_HEADER;

  @Autowired private MockMvc mockMvc;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private DeviceAuthorizationRepository deviceAuthorizationRepository;

  @Autowired private ApplicationContext applicationContext;

  private AuthTestSupport.TestIdentity identity;

  @AfterEach
  void deleteIdentityAndDeviceAuthorizations() {
    deviceAuthorizationRepository.deleteAll();
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @DisplayName("Should reject graphql when unauthenticated")
  void shouldRejectGraphQlWhenUnauthenticated() throws Exception {
    mockMvc
        .perform(post("/graphql").contentType(MediaType.APPLICATION_JSON).content(ACCOUNT_QUERY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("Should reject images when unauthenticated")
  void shouldRejectImagesWhenUnauthenticated() throws Exception {
    mockMvc
        .perform(get("/api/images/{id}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("Should reject images when account scoped")
  void shouldRejectImagesWhenAccountScoped() throws Exception {
    identity = authTestSupport.createIdentity();

    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .with(bearer(authTestSupport.accountBearer(identity))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("Should permit images when profile scoped")
  void shouldPermitImagesWhenProfileScoped() throws Exception {
    identity = authTestSupport.createIdentity();

    mockMvc
        .perform(
            get("/api/images/{id}", UUID.randomUUID())
                .with(bearer(authTestSupport.profileBearer(identity))))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should reject stream endpoints when the playback token is missing")
  void shouldRejectStreamEndpointsWhenPlaybackTokenMissing() throws Exception {
    // Streams demand SCOPE_PLAYBACK carried in the ?t= parameter — headers and cookies never
    // reach them, and API tokens never authorize playback.
    mockMvc
        .perform(get("/api/stream/{id}/multivariant.m3u8", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Should reject graphql when playback scoped")
  void shouldRejectGraphQlWhenPlaybackScoped() throws Exception {
    identity = authTestSupport.createIdentity();

    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACCOUNT_QUERY)
                .with(bearer(playbackBearer(UUID.randomUUID()))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("Should permit requests when targeting auth or health endpoints")
  void shouldPermitRequestsWhenTargetingAuthOrHealthEndpoints() throws Exception {
    mockMvc.perform(get("/api/auth/status")).andExpect(status().isOk());
    // The contract is reachability, not health: a DOWN indicator answers 503, never 401/403.
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
  }

  @Test
  @DisplayName("Should not grant cross-origin json preflight when the origin is hostile")
  void shouldNotGrantCrossOriginJsonPreflightWhenOriginIsHostile() throws Exception {
    mockMvc
        .perform(
            options("/api/auth/login")
                .header("Origin", "https://attacker.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  @DisplayName("Should serve jwks unauthenticated")
  void shouldServeJwksUnauthenticated() throws Exception {
    // Public verification keys are public: the transcode tier fetches them with no credentials.
    mockMvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should permit device issuance and polling when the caller has no credentials")
  void shouldPermitDeviceIssuanceAndPollingWhenCallerHasNoCredentials() throws Exception {
    // A TV has no session yet, so these two must be reachable anonymously — and, unlike the
    // authenticated pairing endpoints, they carry no cookies for CSRF to protect.
    mockMvc
        .perform(
            post("/api/auth/device/code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceName\": \"Apple TV\"}"))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));

    mockMvc
        .perform(
            post("/api/auth/device/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceCode\": \"unknown\"}"))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
  }

  @Test
  @DisplayName("Should never 401 a device endpoint when only a stale access cookie is present")
  void shouldNeverUnauthorizeDeviceEndpointWhenOnlyStaleAccessCookiePresent() throws Exception {
    // permitAll settles authentication, not CSRF. The Path=/ access cookie rides every same-origin
    // request, so the manually wired credential-shaped CSRF filter still covers these routes — but
    // the refusal must come from CSRF (403), never from the bearer resolver expiring the cookie.
    var staleAccessCookie = new Cookie(AuthCookies.ACCESS_COOKIE, "stale-access-token");

    mockMvc
        .perform(
            post("/api/auth/device/code")
                .cookie(staleAccessCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceName\": \"Apple TV\"}"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            post("/api/auth/device/token")
                .cookie(staleAccessCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceCode\": \"unknown\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should exempt a device poll from CSRF when it carries a bearer token")
  void shouldExemptDevicePollFromCsrfWhenBearerTokenPresent() throws Exception {
    // The TV's own shape: an Authorization header and no cookies, which is never CSRF-able.
    mockMvc
        .perform(
            post("/api/auth/device/token")
                .header("Authorization", "Bearer not-a-real-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceCode\": \"unknown\"}"))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
  }

  @Test
  @DisplayName("Should require authentication when pairing lookup or decision is requested")
  void shouldRequireAuthenticationWhenPairingLookupOrDecisionRequested() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/device/authorizations/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userCode\": \"BCDF-GHJK\"}"))
        .andExpect(status().isUnauthorized());

    mockMvc
        .perform(
            post("/api/auth/device/authorizations/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userCode\": \"BCDF-GHJK\", \"decision\": \"APPROVE\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName(
      "Should reject a cookie-authenticated pairing decision when the CSRF token is missing")
  void shouldRejectCookieAuthenticatedPairingDecisionWhenCsrfTokenMissing() throws Exception {
    identity = authTestSupport.createIdentity();

    // Cookie-carried credentials are the ambient ones CSRF exists to cover; the manually wired
    // filter must reach this route.
    mockMvc
        .perform(
            post("/api/auth/device/authorizations/decision")
                .cookie(
                    new Cookie(AuthCookies.ACCESS_COOKIE, authTestSupport.accountBearer(identity)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userCode\": \"BCDF-GHJK\", \"decision\": \"APPROVE\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should permit health when stale access cookie attached")
  void shouldPermitHealthWhenStaleAccessCookieAttached() throws Exception {
    mockMvc
        .perform(
            get("/actuator/health")
                .cookie(new Cookie(AuthCookies.ACCESS_COOKIE, "stale-access-token")))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
  }

  @Test
  @DisplayName("Should deny non-health actuator endpoints when account scoped")
  void shouldDenyNonHealthActuatorEndpointsWhenAccountScoped() throws Exception {
    identity = authTestSupport.createIdentity();

    // Operational surfaces (metrics, info) are not for ordinary accounts; the observability
    // profile exposes them, so the chain refuses everything under /actuator except health.
    mockMvc
        .perform(get("/actuator/metrics").with(bearer(authTestSupport.accountBearer(identity))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Should permit graphql when account scoped")
  void shouldPermitGraphQlWhenAccountScoped() throws Exception {
    identity = authTestSupport.createIdentity();

    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACCOUNT_QUERY)
                .with(bearer(authTestSupport.accountBearer(identity))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  @Test
  @DisplayName("Should permit graphql when profile scoped through hierarchy")
  void shouldPermitGraphQlWhenProfileScopedThroughHierarchy() throws Exception {
    identity = authTestSupport.createIdentity();

    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACCOUNT_QUERY)
                .with(bearer(authTestSupport.profileBearer(identity))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  @Test
  @DisplayName("Should publish scope hierarchy for security auto detection")
  void shouldPublishScopeHierarchyForSecurityAutoDetection() {
    assertThat(applicationContext.getBeanProvider(RoleHierarchy.class).getIfUnique()).isNotNull();
  }

  @Test
  @DisplayName(
      "Should issue a host-bound script-readable csrf cookie when status is requested unauthenticated")
  void shouldIssueHostBoundScriptReadableCsrfCookieWhenStatusRequestedUnauthenticated()
      throws Exception {
    // The SPA's boot request. Everything below depends on this: a browser holds the host-bound
    // anti-CSRF nonce before it can ever POST a login, and a native client never asks for one.
    var cookie =
        mockMvc
            .perform(get("/api/auth/status"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getCookie("__Host-XSRF-TOKEN");

    assertThat(cookie).isNotNull();
    assertThat(cookie.isHttpOnly()).isFalse();
  }

  @Test
  @DisplayName("Should issue a host-prefixed csrf cookie when a token is requested")
  void shouldIssueHostPrefixedCsrfCookieWhenTokenIsRequested() throws Exception {
    var cookie = freshCsrfCookie();

    assertAll(
        () -> assertThat(cookie.getName()).isEqualTo("__Host-XSRF-TOKEN"),
        () -> assertThat(cookie.getSecure()).isTrue(),
        () -> assertThat(cookie.getPath()).isEqualTo("/"),
        () -> assertThat(cookie.getDomain()).isNull(),
        () -> assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax"));
  }

  @Test
  @DisplayName("Should reject a cookie-bearing login when no csrf token accompanies it")
  void shouldRejectCookieBearingLoginWhenNoCsrfTokenAccompaniesIt() throws Exception {
    // Login-CSRF: the credential is in the body, so no auth cookie rides the request — but the
    // browser holds the origin's CSRF cookie, and that is the population CSRF must cover.
    mockMvc
        .perform(
            post("/api/auth/login")
                .cookie(freshCsrfCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("nobody-" + UUID.randomUUID() + "@example.com", true)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_REQUIRED"));
  }

  @Test
  @DisplayName(
      "Should reject graphql and remint its csrf cookie when cookie authentication lacks a token")
  void shouldRejectGraphQlAndRemintCsrfCookieWhenCookieAuthenticationLacksToken() throws Exception {
    identity = authTestSupport.createIdentity();

    var response =
        mockMvc
            .perform(
                post("/graphql")
                    .cookie(
                        new Cookie(
                            AuthCookies.ACCESS_COOKIE, authTestSupport.accountBearer(identity)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(ACCOUNT_QUERY))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_TOKEN_REQUIRED"))
            .andReturn()
            .getResponse();

    assertThat(response.getCookie(AuthCookies.CSRF_COOKIE)).isNotNull();
  }

  @Test
  @DisplayName("Should reject a request when only an unprefixed csrf cookie is echoed")
  void shouldRejectRequestWhenOnlyUnprefixedCsrfCookieIsEchoed() throws Exception {
    identity = authTestSupport.createIdentity();
    var attackerChosenToken = "attacker-chosen-token";

    mockMvc
        .perform(
            post("/graphql")
                .cookie(
                    new Cookie(AuthCookies.ACCESS_COOKIE, authTestSupport.accountBearer(identity)),
                    new Cookie("XSRF-TOKEN", attackerChosenToken))
                .header(CSRF_HEADER, attackerChosenToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(ACCOUNT_QUERY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_REQUIRED"));
  }

  @Test
  @DisplayName("Should reject setup when a csrf cookie is not echoed")
  void shouldRejectSetupWhenCsrfCookieIsNotEchoed() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/setup")
                .cookie(freshCsrfCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email": "admin@example.com", "displayName": "Admin", \
                    "password": "test-password", "householdName": "Home", \
                    "profileName": "Admin", "cookieMode": true} \
                    """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CSRF_TOKEN_REQUIRED"));
  }

  @Test
  @DisplayName("Should permit cookie-mode login when csrf accompanies a stale access cookie")
  void shouldPermitCookieModeLoginWhenCsrfAccompaniesStaleAccessCookie() throws Exception {
    identity = authTestSupport.createIdentity();
    var csrfCookie = freshCsrfCookie();

    // The wedge: a stale access cookie must not lock a user out of the one call that replaces it.
    var response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .cookie(csrfCookie, new Cookie(AuthCookies.ACCESS_COOKIE, "stale-access-token"))
                    .header(CSRF_HEADER, csrfCookie.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(identity.account().getEmail(), true)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andReturn()
            .getResponse();

    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
        .anySatisfy(header -> assertThat(header).startsWith(AuthCookies.ACCESS_COOKIE + "="));
  }

  @Test
  @DisplayName("Should permit a bearer-mode login when no cookies and no csrf token ride it")
  void shouldPermitBearerModeLoginWhenNoCookiesAndNoCsrfTokenRideIt() throws Exception {
    identity = authTestSupport.createIdentity();

    // The native/tvOS shape. Tokens come back in the body only, so nothing ambient is established.
    var response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(identity.account().getEmail(), false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andReturn()
            .getResponse();

    // The filter offers a CSRF token to everyone, so the contract is that no *credential* cookie
    // is written: a native client that ignores Set-Cookie stays exempt on its next login.
    assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
        .noneSatisfy(header -> assertThat(header).startsWith(AuthCookies.ACCESS_COOKIE + "="))
        .noneSatisfy(header -> assertThat(header).startsWith(AuthCookies.REFRESH_COOKIE + "="));
  }

  @Test
  @DisplayName("Should permit bearer-mode login when a retained csrf cookie is echoed")
  void shouldPermitBearerModeLoginWhenRetainedCsrfCookieIsEchoed() throws Exception {
    identity = authTestSupport.createIdentity();
    var csrfCookie = freshCsrfCookie();

    mockMvc
        .perform(
            post("/api/auth/login")
                .cookie(csrfCookie)
                .header(CSRF_HEADER, csrfCookie.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(identity.account().getEmail(), false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.refreshToken").isNotEmpty());
  }

  @Test
  @DisplayName("Should permit a bearer-mode refresh when no cookies and no csrf token ride it")
  void shouldPermitBearerModeRefreshWhenNoCookiesAndNoCsrfTokenRideIt() throws Exception {
    identity = authTestSupport.createIdentity();

    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\": \"%s\"}".formatted(identity.rawRefreshToken())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty());
  }

  @ParameterizedTest(name = "Should reject {1} on {0}")
  @MethodSource("nonJsonAuthRequests")
  @DisplayName("Should reject an auth mutation when its content type is simple cross-origin")
  void shouldRejectAuthMutationWhenContentTypeIsSimpleCrossOrigin(
      String path, MediaType contentType, String body) throws Exception {
    mockMvc
        .perform(post(path).contentType(contentType).content(body))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  @DisplayName(
      "Should not expire the csrf cookie before auth cookies when cookie-mode login succeeds")
  void shouldNotExpireCsrfCookieBeforeAuthCookiesWhenCookieModeLoginSucceeds() throws Exception {
    identity = authTestSupport.createIdentity();
    var csrfCookie = freshCsrfCookie();

    var response =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .cookie(csrfCookie)
                    .header(CSRF_HEADER, csrfCookie.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(identity.account().getEmail(), true)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    // A session-scoped guard outlived by a 30-day credential is the wedge: close the browser and
    // the token dies while the auth cookies survive, so an unsafe request made before the next
    // safe boot request receives a recoverable 403.
    var accessCookie = response.getCookie(AuthCookies.ACCESS_COOKIE);
    var refreshCookie = response.getCookie(AuthCookies.REFRESH_COOKIE);
    assertThat(accessCookie).isNotNull();
    assertThat(refreshCookie).isNotNull();
    assertThat(csrfCookie.getMaxAge())
        .isGreaterThanOrEqualTo(accessCookie.getMaxAge())
        .isGreaterThanOrEqualTo(refreshCookie.getMaxAge());
  }

  @Test
  @DisplayName("Should renew the csrf cookie when the refresh cookie rotates")
  void shouldRenewCsrfCookieWhenRefreshCookieRotates() throws Exception {
    identity = authTestSupport.createIdentity();
    var csrfCookie = freshCsrfCookie();
    var loginResponse =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .cookie(csrfCookie)
                    .header(CSRF_HEADER, csrfCookie.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(identity.account().getEmail(), true)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var refreshCookie = loginResponse.getCookie(AuthCookies.REFRESH_COOKIE);
    assertThat(refreshCookie).isNotNull();

    var refreshResponse =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .cookie(csrfCookie, refreshCookie)
                    .header(CSRF_HEADER, csrfCookie.getValue())
                    .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    var renewedCsrfCookie = refreshResponse.getCookie(AuthCookies.CSRF_COOKIE);
    assertThat(renewedCsrfCookie).isNotNull();
    assertThat(renewedCsrfCookie.getValue()).isEqualTo(csrfCookie.getValue());
    assertThat(renewedCsrfCookie.getMaxAge()).isEqualTo(refreshCookie.getMaxAge());
  }

  private Cookie freshCsrfCookie() throws Exception {
    var cookie =
        mockMvc
            .perform(get("/api/auth/status"))
            .andReturn()
            .getResponse()
            .getCookie(AuthCookies.CSRF_COOKIE);
    assertThat(cookie).isNotNull();
    return cookie;
  }

  private String loginBody(String email, boolean cookieMode) {
    return """
        {"email": "%s", "password": "%s", "deviceName": "Test", "cookieMode": %s}"""
        .formatted(email, authTestSupport.password(), cookieMode);
  }

  private static Stream<Arguments> nonJsonAuthRequests() {
    return Stream.of(
        Arguments.of("/api/auth/setup", MediaType.TEXT_PLAIN, "not-json"),
        Arguments.of("/api/auth/setup", MediaType.APPLICATION_FORM_URLENCODED, "email=a"),
        Arguments.of("/api/auth/setup", MediaType.MULTIPART_FORM_DATA, "not-a-multipart-body"),
        Arguments.of("/api/auth/login", MediaType.TEXT_PLAIN, "not-json"),
        Arguments.of("/api/auth/login", MediaType.APPLICATION_FORM_URLENCODED, "email=a"),
        Arguments.of("/api/auth/login", MediaType.MULTIPART_FORM_DATA, "not-a-multipart-body"),
        Arguments.of("/api/auth/refresh", MediaType.TEXT_PLAIN, "not-json"),
        Arguments.of("/api/auth/refresh", MediaType.APPLICATION_FORM_URLENCODED, "token=a"),
        Arguments.of("/api/auth/refresh", MediaType.MULTIPART_FORM_DATA, "not-a-multipart-body"));
  }

  private String playbackBearer(UUID streamSessionId) {
    return authTestSupport.playbackBearer(identity, streamSessionId);
  }
}
