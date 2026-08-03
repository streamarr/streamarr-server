package com.streamarr.server.controllers.auth.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.DeviceAuthProperties;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.auth.TokenContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@Tag("IntegrationTest")
@DisplayName("Device Pairing Throttle Integration Tests")
class DeviceThrottleIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private DeviceAuthorizationRepository authorizationRepository;

  @Autowired private AccessTokenIssuer accessTokenIssuer;

  @Autowired private RefreshTokenService refreshTokenService;

  @Autowired private DeviceAuthProperties properties;

  private final List<UUID> accountIds = new ArrayList<>();

  @AfterEach
  void deleteSeededRows() {
    authorizationRepository.deleteAll();
    accountIds.forEach(userAccountRepository::deleteById);
    accountIds.clear();
  }

  @Test
  @DisplayName("Should throttle decision once lookup has spent the shared guessing budget")
  void shouldThrottleDecisionOnceLookupHasSpentSharedGuessingBudget() throws Exception {
    var approver = seedAccount();
    var bearer = bearerFor(approver);

    // Lookup is the enumeration oracle, so its attempts and decision's come from one budget —
    // two budgets would hand an attacker twice the tries against the same code.
    for (var attempt = 0; attempt < properties.maxGuessAttempts(); attempt++) {
      mockMvc.perform(lookup(bearer, "BCDF-GHJK")).andExpect(status().isNotFound());
    }

    mockMvc
        .perform(lookup(bearer, "BCDF-GHJK"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

    mockMvc
        .perform(
            authenticated(bearer, post("/api/auth/device/authorizations/decision"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userCode\": \"BCDF-GHJK\", \"decision\": \"APPROVE\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
  }

  @Test
  @DisplayName("Should budget each approver separately")
  void shouldBudgetEachApproverSeparately() throws Exception {
    var exhausted = bearerFor(seedAccount());
    var untouched = bearerFor(seedAccount());

    for (var attempt = 0; attempt <= properties.maxGuessAttempts(); attempt++) {
      mockMvc.perform(lookup(exhausted, "BCDF-GHJK"));
    }

    mockMvc.perform(lookup(exhausted, "BCDF-GHJK")).andExpect(status().isTooManyRequests());
    mockMvc.perform(lookup(untouched, "BCDF-GHJK")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should refuse issuance with a retry hint once the outstanding cap is reached")
  void shouldRefuseIssuanceWithRetryHintOnceOutstandingCapReached() throws Exception {
    for (var issued = 0; issued < properties.maxOutstandingCodes(); issued++) {
      mockMvc.perform(issueCode()).andExpect(status().isOk());
    }

    var retryAfter =
        mockMvc
            .perform(issueCode())
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
            .andReturn()
            .getResponse()
            .getHeader(HttpHeaders.RETRY_AFTER);

    // With a row-count cap there is no window to measure: the hint is when the oldest code dies.
    assertThat(Long.parseLong(retryAfter))
        .isPositive()
        .isLessThanOrEqualTo(properties.codeTtl().toSeconds());
  }

  private static MockHttpServletRequestBuilder issueCode() {
    return post("/api/auth/device/code")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"deviceName\": \"Apple TV\"}");
  }

  private MockHttpServletRequestBuilder lookup(String bearer, String userCode) {
    return authenticated(bearer, post("/api/auth/device/authorizations/lookup"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userCode\": \"%s\"}".formatted(userCode));
  }

  private static MockHttpServletRequestBuilder authenticated(
      String bearer, MockHttpServletRequestBuilder request) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
  }

  private String bearerFor(UserAccount account) {
    var session = refreshTokenService.createSession(account, "web").session();
    return accessTokenIssuer
        .issue(TokenContext.builder().account(account).session(session).build())
        .value();
  }

  private UserAccount seedAccount() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    accountIds.add(account.getId());
    return account;
  }
}
