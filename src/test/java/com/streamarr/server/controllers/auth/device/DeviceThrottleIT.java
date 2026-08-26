package com.streamarr.server.controllers.auth.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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

@Tag("IntegrationTest")
@ResourceLock("server-bootstrap")
@DisplayName("Device Pairing Throttle Integration Tests")
@Import(AuthTestSupportConfig.class)
class DeviceThrottleIT extends AbstractIntegrationTest {

  private static final int MAXIMUM_FAILURES = 5;
  private static final String UNKNOWN_USER_CODE = "BCDF-GHJK";

  @Autowired private MockMvc mockMvc;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private DeviceAuthorizationRepository authorizationRepository;

  @Autowired private AccessTokenIssuer accessTokenIssuer;

  @Autowired private RefreshTokenService refreshTokenService;

  @Autowired private DeviceAuthProperties properties;

  @Autowired private JdbcTemplate jdbcTemplate;

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
    accountIds.forEach(this::deleteJournaledAttempts);
    authorizationRepository.deleteAll();
    accountIds.forEach(authTestSupport::deleteAccount);
    accountIds.clear();
  }

  @Test
  @DisplayName("Should throttle a decision when lookup has spent the approver's guessing budget")
  void shouldThrottleDecisionWhenLookupHasSpentApproversGuessingBudget() throws Exception {
    var bearer = bearerFor(seedAccount());

    // Lookup is the enumeration oracle, so its attempts and decision's come from one budget —
    // two budgets would hand an attacker twice the tries against the same code.
    for (var attempt = 0; attempt < MAXIMUM_FAILURES; attempt++) {
      mockMvc.perform(lookup(bearer, UNKNOWN_USER_CODE)).andExpect(status().isNotFound());
    }

    mockMvc
        .perform(lookup(bearer, UNKNOWN_USER_CODE))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

    mockMvc
        .perform(decision(bearer, UNKNOWN_USER_CODE, "APPROVE"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
  }

  @Test
  @DisplayName("Should use a separate guessing budget when the approver changes")
  void shouldUseSeparateGuessingBudgetWhenApproverChanges() throws Exception {
    var exhausted = bearerFor(seedAccount());
    var untouched = bearerFor(seedAccount());

    for (var attempt = 0; attempt < MAXIMUM_FAILURES; attempt++) {
      mockMvc.perform(lookup(exhausted, UNKNOWN_USER_CODE)).andExpect(status().isNotFound());
    }

    mockMvc.perform(lookup(exhausted, UNKNOWN_USER_CODE)).andExpect(status().isTooManyRequests());
    mockMvc.perform(lookup(untouched, UNKNOWN_USER_CODE)).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should journal a failure against the approver when the pairing code is unknown")
  void shouldJournalFailureAgainstApproverWhenPairingCodeIsUnknown() throws Exception {
    var approver = seedAccount();

    mockMvc
        .perform(lookup(bearerFor(approver), UNKNOWN_USER_CODE))
        .andExpect(status().isNotFound());

    assertThat(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM credential_attempt
                 WHERE credential_kind = 'DEVICE_PAIRING_CODE'
                   AND account_id = ?
                   AND profile_id IS NULL
                   AND credential_id IS NULL
                   AND result = 'FAILED'
                """,
                Integer.class,
                approver.getId()))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Should preserve the guessing budget when the decision value is invalid")
  void shouldPreserveGuessingBudgetWhenDecisionValueInvalid() throws Exception {
    var approver = seedAccount();
    var bearer = bearerFor(approver);

    for (var attempt = 0; attempt < MAXIMUM_FAILURES; attempt++) {
      mockMvc
          .perform(decision(bearer, UNKNOWN_USER_CODE, "MAYBE"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("INVALID_DECISION"));
    }

    assertThat(journaledAttempts(approver.getId())).isZero();
    mockMvc.perform(lookup(bearer, UNKNOWN_USER_CODE)).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Should spend one budget when unknown lookups and decisions are mixed")
  void shouldSpendOneBudgetWhenUnknownLookupsAndDecisionsAreMixed() throws Exception {
    var bearer = bearerFor(seedAccount());

    for (var attempt = 0; attempt < 3; attempt++) {
      mockMvc.perform(lookup(bearer, UNKNOWN_USER_CODE)).andExpect(status().isNotFound());
    }
    for (var attempt = 0; attempt < MAXIMUM_FAILURES - 3; attempt++) {
      mockMvc.perform(decision(bearer, UNKNOWN_USER_CODE, "DENY")).andExpect(status().isNotFound());
    }

    mockMvc.perform(lookup(bearer, UNKNOWN_USER_CODE)).andExpect(status().isTooManyRequests());
  }

  @Test
  @DisplayName("Should journal no attempt when the user code is malformed")
  void shouldJournalNoAttemptWhenUserCodeIsMalformed() throws Exception {
    var approver = seedAccount();
    var bearer = bearerFor(approver);

    mockMvc
        .perform(lookup(bearer, "not a code"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_USER_CODE"));
    mockMvc
        .perform(decision(bearer, "not a code", "DENY"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_USER_CODE"));

    assertThat(journaledAttempts(approver.getId())).isZero();
  }

  @Test
  @DisplayName("Should journal exactly one attempt when a real pairing code is decided")
  void shouldJournalExactlyOneAttemptWhenRealPairingCodeIsDecided() throws Exception {
    var approver = seedAccount();
    var userCode = issueUserCode();

    mockMvc.perform(decision(bearerFor(approver), userCode, "DENY")).andExpect(status().isOk());

    assertThat(journaledAttempts(approver.getId())).isEqualTo(1);
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
        mockMvc
            .perform(issueCode())
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(response, "$.userCode");
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

  private int journaledAttempts(UUID accountId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM credential_attempt WHERE account_id = ?", Integer.class, accountId);
  }

  private void deleteJournaledAttempts(UUID accountId) {
    jdbcTemplate.update("DELETE FROM credential_attempt WHERE account_id = ?", accountId);
  }
}
