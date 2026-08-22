package com.streamarr.server.controllers.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.TokenClaims;
import com.streamarr.server.services.auth.TokenContext;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * POST /api/auth/reauth (ADR 0024 §Fresh reauthentication): an authenticated step-up ceremony that
 * returns a replacement access token only, carrying {@code reauthenticated_at}, expiring at the
 * earlier of the configured window or the source token's expiry. Login and refresh never start the
 * claim; derivation preserves it without extending it; refresh removes it.
 */
@Tag("IntegrationTest")
@DisplayName("Reauthentication Endpoint Integration Tests")
class ReauthEndpointIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTokenProperties tokenProperties;
  @Autowired private AccessTokenIssuer accessTokenIssuer;

  private AuthTestSupport.TestIdentity identity;

  @BeforeEach
  void setUp() {
    identity = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should return a replacement access token only when the password is correct")
  void shouldReturnReplacementAccessTokenOnlyWhenPasswordCorrect() throws Exception {
    var source = authTestSupport.accountBearer(identity);
    var sourceExpiry = jwtDecoder.decode(source).getExpiresAt();

    var body =
        objectMapper.readTree(
            reauth(source, authTestSupport.password())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.accessTokenExpiresAt").exists())
                .andExpect(jsonPath("$.scope").value("account"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString());

    var replacement = jwtDecoder.decode(body.get("accessToken").asString());
    assertThat(reauthenticatedAt(replacement)).isNotNull();
    assertThat(replacement.getClaimAsString(TokenClaims.SESSION_ID))
        .isEqualTo(identity.session().getId().toString());
    var windowEnd = reauthenticatedAt(replacement).plus(tokenProperties.reauthenticationWindow());
    var expectedExpiry = sourceExpiry.isBefore(windowEnd) ? sourceExpiry : windowEnd;
    assertThat(replacement.getExpiresAt()).isEqualTo(expectedExpiry);
    assertThat(Instant.parse(body.get("accessTokenExpiresAt").asString()))
        .isEqualTo(expectedExpiry);
  }

  @Test
  @DisplayName("Should preserve profile scope and selection when reauthenticating")
  void shouldPreserveProfileScopeAndSelectionWhenReauthenticating() throws Exception {
    var visitedHouseholdId = UUID.randomUUID();
    var source =
        accessTokenIssuer
            .issue(
                TokenContext.builder()
                    .account(identity.account())
                    .session(identity.session())
                    .contextHouseholdId(visitedHouseholdId)
                    .profileId(identity.profile().getId())
                    .build())
            .value();
    var sourceClaims = jwtDecoder.decode(source);

    var body =
        objectMapper.readTree(
            reauth(source, authTestSupport.password())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("profile"))
                .andReturn()
                .getResponse()
                .getContentAsString());

    var replacement = jwtDecoder.decode(body.get("accessToken").asString());
    assertThat(replacement.getClaimAsString(TokenClaims.SESSION_ID))
        .isEqualTo(sourceClaims.getClaimAsString(TokenClaims.SESSION_ID));
    assertThat(replacement.getClaimAsString(TokenClaims.HOUSEHOLD_ID))
        .isEqualTo(sourceClaims.getClaimAsString(TokenClaims.HOUSEHOLD_ID));
    assertThat(replacement.getClaimAsString(TokenClaims.CONTEXT_HOUSEHOLD_ID))
        .isEqualTo(visitedHouseholdId.toString())
        .isEqualTo(sourceClaims.getClaimAsString(TokenClaims.CONTEXT_HOUSEHOLD_ID));
    assertThat(replacement.getClaimAsString(TokenClaims.PROFILE_ID))
        .isEqualTo(identity.profile().getId().toString());
    assertThat(reauthenticatedAt(replacement)).isNotNull();
  }

  @Test
  @DisplayName("Should reject reauthentication when the password is blank")
  void shouldRejectReauthenticationWhenPasswordBlank() throws Exception {
    reauth(authTestSupport.accountBearer(identity), "  ").andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Should reject reauthentication when the password is missing")
  void shouldRejectReauthenticationWhenPasswordMissing() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/reauth")
                .contentType(MediaType.APPLICATION_JSON)
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + authTestSupport.accountBearer(identity))
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("Should never start the claim at login and remove it on refresh")
  void shouldNeverStartClaimAtLoginAndRemoveItOnRefresh() throws Exception {
    var login =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"email": "%s", "password": "%s", "cookieMode": false}
                            """
                                .formatted(
                                    identity.account().getEmail(), authTestSupport.password())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(reauthenticatedAt(jwtDecoder.decode(login.get("accessToken").asString()))).isNull();

    reauth(login.get("accessToken").asString(), authTestSupport.password())
        .andExpect(status().isOk());

    var refreshed =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"refreshToken\": \"%s\"}"
                                .formatted(login.get("refreshToken").asString())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(reauthenticatedAt(jwtDecoder.decode(refreshed.get("accessToken").asString())))
        .isNull();
  }

  @Test
  @DisplayName("Should preserve the claim without extending expiry when a selection derives")
  void shouldPreserveClaimWithoutExtendingExpiryWhenSelectionDerives() throws Exception {
    var reauthenticated =
        objectMapper.readTree(
            reauth(authTestSupport.accountBearer(identity), authTestSupport.password())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var fresh = jwtDecoder.decode(reauthenticated.get("accessToken").asString());

    var selected =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/api/auth/select-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + fresh.getTokenValue())
                        .content(
                            "{\"profileId\": \"%s\", \"cookieMode\": false}"
                                .formatted(identity.profile().getId())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    var derived = jwtDecoder.decode(selected.get("accessToken").asString());
    assertThat(reauthenticatedAt(derived)).isEqualTo(reauthenticatedAt(fresh));
    assertThat(derived.getExpiresAt()).isBeforeOrEqualTo(fresh.getExpiresAt());
  }

  @Test
  @DisplayName("Should reject reauthentication when the password is wrong")
  void shouldRejectReauthenticationWhenPasswordWrong() throws Exception {
    reauth(authTestSupport.accountBearer(identity), "not the password")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
  }

  @Test
  @DisplayName("Should throttle reauthentication after repeated wrong passwords")
  void shouldThrottleReauthenticationAfterRepeatedWrongPasswords() throws Exception {
    var bearer = authTestSupport.accountBearer(identity);
    for (var attempt = 0; attempt < 5; attempt++) {
      reauth(bearer, "wrong-" + attempt).andExpect(status().isUnauthorized());
    }

    reauth(bearer, authTestSupport.password())
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
  }

  @Test
  @DisplayName("Should reject reauthentication when the Account is disabled")
  void shouldRejectReauthenticationWhenAccountDisabled() throws Exception {
    var bearer = authTestSupport.accountBearer(identity);
    var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
    account.setEnabled(false);
    userAccountRepository.saveAndFlush(account);

    reauth(bearer, authTestSupport.password())
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
  }

  @Test
  @DisplayName("Should reject reauthentication when the session is revoked")
  void shouldRejectReauthenticationWhenSessionRevoked() throws Exception {
    var bearer = authTestSupport.accountBearer(identity);
    authSessionRepository.revoke(
        identity.session().getId(), SessionRevocationReason.LOGOUT, Instant.now());

    reauth(bearer, authTestSupport.password()).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Should reject reauthentication when no identity is present")
  void shouldRejectReauthenticationWhenNoIdentityPresent() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/reauth")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\": \"irrelevant\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Should reject reauthentication when the token is playback scoped")
  void shouldRejectReauthenticationWhenTokenIsPlaybackScoped() throws Exception {
    var playback = authTestSupport.playbackBearer(identity, UUID.randomUUID());

    reauth(playback, authTestSupport.password()).andExpect(status().isForbidden());
  }

  private ResultActions reauth(String bearer, String password) throws Exception {
    return mockMvc.perform(
        post("/api/auth/reauth")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content("{\"password\": \"%s\"}".formatted(password)));
  }

  private static Instant reauthenticatedAt(Jwt jwt) {
    return jwt.getClaimAsInstant(TokenClaims.REAUTHENTICATED_AT);
  }
}
