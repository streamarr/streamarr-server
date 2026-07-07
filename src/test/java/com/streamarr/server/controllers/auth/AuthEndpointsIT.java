package com.streamarr.server.controllers.auth;

import static com.streamarr.server.jooq.generated.tables.Household.HOUSEHOLD;
import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
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
import jakarta.servlet.http.Cookie;
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

  private UserAccount account;
  private Household household;
  private Profile profile;
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
