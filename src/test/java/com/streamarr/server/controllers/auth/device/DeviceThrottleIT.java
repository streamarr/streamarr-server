package com.streamarr.server.controllers.auth.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.DeviceAuthProperties;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.auth.TokenContext;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupportConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@ResourceLock("server-bootstrap")
@DisplayName("Device Pairing Throttle Integration Tests")
@Import(AuthTestSupportConfig.class)
class DeviceThrottleIT extends AbstractIntegrationTest {

  private static final int MAXIMUM_FAILURES = 5;

  @Autowired private MockMvc mockMvc;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private DeviceAuthorizationRepository authorizationRepository;

  @Autowired private AccessTokenIssuer accessTokenIssuer;

  @Autowired private RefreshTokenService refreshTokenService;

  @Autowired private DeviceAuthProperties properties;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private ObjectMapper objectMapper;

  private final List<UUID> accountIds = new ArrayList<>();

  @BeforeEach
  void seedBaseline() {
    authTestSupport.claimBootstrap();
    deleteSeededRows();
  }

  @AfterEach
  void restoreBaseline() {
    // Unclaim first: T4 only enforces while a claim exists, and the deletions below may remove
    // the database's last enabled ServerAdmin.
    authTestSupport.unclaimBootstrap();
    deleteSeededRows();
  }

  private void deleteSeededRows() {
    jdbcTemplate.update("DELETE FROM credential_attempt");
    authorizationRepository.deleteAll();
    accountIds.forEach(authTestSupport::deleteAccount);
    accountIds.clear();
  }

  @Test
  @DisplayName("Should share one expired pairing-code limit across approvers and operations")
  void shouldThrottleDecisionWhenLookupHasExhaustedSharedAttemptLimit() throws Exception {
    var exhausted = bearerFor(seedAccount());
    var otherApprover = bearerFor(seedAccount());
    var userCode = issueUserCode();
    expireOutstandingCodes();

    // Lookup and decision verify the same credential target and therefore share one limit.
    for (var attempt = 0; attempt < MAXIMUM_FAILURES; attempt++) {
      mockMvc.perform(lookup(exhausted, userCode)).andExpect(status().isNotFound());
    }

    mockMvc
        .perform(lookup(otherApprover, userCode))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

    mockMvc
        .perform(
            authenticated(otherApprover, post("/api/auth/device/authorizations/decision"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userCode\": \"%s\", \"decision\": \"APPROVE\"}".formatted(userCode)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
  }

  @Test
  @DisplayName("Should preserve the attempt limit when the decision value is invalid")
  void shouldPreserveAttemptLimitWhenDecisionValueInvalid() throws Exception {
    var bearer = bearerFor(seedAccount());

    for (var attempt = 0; attempt < MAXIMUM_FAILURES; attempt++) {
      mockMvc
          .perform(decision(bearer, "BCDF-GHJK", "MAYBE"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_DECISION"));
    }

    mockMvc.perform(lookup(bearer, "BCDF-GHJK")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should journal unknown pairing codes without subject throttling")
  void shouldJournalUnknownPairingCodesWithoutSubjectThrottling() throws Exception {
    var bearer = bearerFor(seedAccount());

    for (var attempt = 0; attempt < MAXIMUM_FAILURES * 2; attempt++) {
      mockMvc.perform(lookup(bearer, "BCDF-GHJK")).andExpect(status().isNotFound());
    }

    assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM credential_attempt
                 WHERE credential_kind = 'DEVICE_PAIRING_CODE'
                   AND account_id IS NULL
                   AND profile_id IS NULL
                   AND credential_id IS NULL
                   AND result = 'FAILED'
                """,
                Integer.class))
        .isEqualTo(MAXIMUM_FAILURES * 2);
  }

  @Test
  @DisplayName("Should refuse issuance with a retry hint when the outstanding cap is reached")
  void shouldRefuseIssuanceWithRetryHintWhenOutstandingCapReached() throws Exception {
    for (var issued = 0; issued < properties.maxOutstandingCodes(); issued++) {
      mockMvc.perform(issueCode()).andExpect(status().isOk());
    }

    var response =
        mockMvc
            .perform(issueCode())
            .andExpect(status().isTooManyRequests())
            .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
            .andExpect(jsonPath("$.*", hasSize(2)))
            .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
            .andExpect(jsonPath("$.message").value("Too many attempts; try again later."))
            .andReturn()
            .getResponse();
    var retryAfter = response.getHeader(HttpHeaders.RETRY_AFTER);

    // With a row-count cap there is no window to measure: the hint is when the oldest code dies.
    assertThat(Long.parseLong(retryAfter))
        .isPositive()
        .isLessThanOrEqualTo(properties.codeTtl().toSeconds());
  }

  private static MockHttpServletRequestBuilder issueCode() {
    return post("/api/auth/device/code")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"deviceName\": \"Apple TV\", \"esn\": \"esn-1\"}");
  }

  private String issueUserCode() throws Exception {
    var response =
        mockMvc.perform(issueCode()).andExpect(status().isOk()).andReturn().getResponse();
    return objectMapper.readTree(response.getContentAsString()).get("userCode").asString();
  }

  private void expireOutstandingCodes() {
    jdbcTemplate.update("UPDATE device_authorization SET expires_at = now() - interval '1 second'");
  }

  private MockHttpServletRequestBuilder lookup(String bearer, String userCode) {
    return authenticated(bearer, post("/api/auth/device/authorizations/lookup"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userCode\": \"%s\"}".formatted(userCode));
  }

  private MockHttpServletRequestBuilder decision(String bearer, String userCode, String decision) {
    return authenticated(bearer, post("/api/auth/device/authorizations/decision"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"userCode": "%s", "decision": "%s"}
            """
                .formatted(userCode, decision));
  }

  private static MockHttpServletRequestBuilder authenticated(
      String bearer, MockHttpServletRequestBuilder request) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
  }

  private String bearerFor(UserAccount account) {
    var session = refreshTokenService.createSession(account, "web").session();
    return accessTokenIssuer.issue(TokenContext.of(account, session)).value();
  }

  private UserAccount seedAccount() {
    var account = authTestSupport.createAccount();
    accountIds.add(account.getId());
    return account;
  }
}
