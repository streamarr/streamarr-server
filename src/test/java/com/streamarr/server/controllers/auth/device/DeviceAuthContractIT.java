package com.streamarr.server.controllers.auth.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.auth.TokenContext;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupportConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Pins {@code /api/auth/device/**} and the status extension as an executable client contract. */
@Tag("IntegrationTest")
@DisplayName("Device Auth Contract Integration Tests")
@Import(AuthTestSupportConfig.class)
class DeviceAuthContractIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private DeviceAuthorizationRepository authorizationRepository;

  @Autowired private AccessTokenIssuer accessTokenIssuer;

  @Autowired private RefreshTokenService refreshTokenService;

  @Autowired private ObjectMapper objectMapper;

  private final List<UUID> accountIds = new ArrayList<>();

  @AfterEach
  void deleteSeededRows() {
    authorizationRepository.deleteAll();
    accountIds.forEach(authTestSupport::deleteAccount);
    accountIds.clear();
  }

  @Test
  @DisplayName("Should report both required status flags when authentication status is requested")
  void shouldReportBothRequiredStatusFlagsWhenAuthenticationStatusRequested() throws Exception {
    var body =
        readJson(
            mockMvc
                .perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(uncacheable()));

    assertThat(fieldNamesOf(body))
        .containsExactlyInAnyOrder("setupComplete", "devicePairingEnabled");
    assertThat(body.get("devicePairingEnabled").asBoolean()).isTrue();
  }

  @Test
  @DisplayName(
      "Should issue an absolute verification URI and server timings when pairing is configured")
  void shouldIssueAbsoluteVerificationUriAndServerTimingsWhenPairingConfigured() throws Exception {
    var body = issueCode("Apple TV");

    assertThat(fieldNamesOf(body))
        .containsExactlyInAnyOrder(
            "deviceCode", "userCode", "verificationUri", "interval", "expiresIn");
    assertThat(body.get("verificationUri").asString()).isEqualTo("https://home.example.test/link");
    assertThat(body.get("userCode").asString())
        .matches("[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}");
    assertThat(body.get("deviceCode").asString()).hasSize(43);
    assertThat(body.get("interval").asInt()).isEqualTo(5);
    assertThat(body.get("expiresIn").asLong()).isEqualTo(600);
  }

  @Test
  @DisplayName(
      "Should reject issuance and use a fallback name when the request omits device fields")
  void shouldRejectIssuanceAndUseFallbackNameWhenRequestOmitsDeviceFields() throws Exception {
    // ADR 0024 §Devices: the registration the winning poll creates is keyed by hardware
    // identity, so a body-less request can no longer mint a code.
    var refused =
        readJson(mockMvc.perform(post("/api/auth/device/code")).andExpect(status().isBadRequest()));
    assertThat(refused.get("code").asString()).isEqualTo("ESN_REQUIRED");

    var body =
        readJson(
            mockMvc
                .perform(
                    post("/api/auth/device/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"esn\": \"esn-contract\"}"))
                .andExpect(status().isOk())
                .andExpect(uncacheable()));

    assertThat(body.get("deviceCode").asString()).hasSize(43);
    assertThat(authorizationRepository.findAll())
        .singleElement()
        .satisfies(row -> assertThat(row.getDeviceName()).isEqualTo("Unknown device"));
  }

  @ParameterizedTest(name = "Should reject unreadable required body [{index}]")
  @NullSource
  @ValueSource(strings = "{")
  @DisplayName("Should use the pinned error body when a required request body is unreadable")
  void shouldUsePinnedErrorBodyWhenRequiredRequestBodyUnreadable(String content) throws Exception {
    var approver = seedAccount();
    var tokenBody =
        readJson(
            mockMvc
                .perform(jsonRequest(post("/api/auth/device/token"), content))
                .andExpect(status().isBadRequest())
                .andExpect(uncacheable()));
    var lookupBody =
        readJson(
            mockMvc
                .perform(
                    jsonRequest(
                        authenticated(approver, post("/api/auth/device/authorizations/lookup")),
                        content))
                .andExpect(status().isBadRequest())
                .andExpect(uncacheable()));
    var decisionBody =
        readJson(
            mockMvc
                .perform(
                    jsonRequest(
                        authenticated(approver, post("/api/auth/device/authorizations/decision")),
                        content))
                .andExpect(status().isBadRequest())
                .andExpect(uncacheable()));

    assertThat(List.of(tokenBody, lookupBody, decisionBody))
        .allSatisfy(
            body ->
                assertErrorBody(
                    body, "INVALID_REQUEST", "The request body is missing or malformed."));
  }

  @Test
  @DisplayName("Should answer with the pinned pending body when an unapproved grant is polled")
  void shouldAnswerWithPinnedPendingBodyWhenUnapprovedGrantPolled() throws Exception {
    var deviceCode = issueCode("Apple TV").get("deviceCode").asString();

    // RFC 8628 §3.2: the interval is the wait between polls, so the first one is never too soon.
    assertErrorBody(
        pollExpectingBadRequest(deviceCode),
        "authorization_pending",
        "The device authorization has not been approved yet.");
  }

  @Test
  @DisplayName("Should answer with the pinned slow-down body when a grant is polled too soon")
  void shouldAnswerWithPinnedSlowDownBodyWhenGrantPolledTooSoon() throws Exception {
    var deviceCode = issueCode("Apple TV").get("deviceCode").asString();
    pollExpectingBadRequest(deviceCode);

    // The second poll lands well inside the interval the first one started.
    assertErrorBody(
        pollExpectingBadRequest(deviceCode),
        "slow_down",
        "Polling too frequently; increase the interval by five seconds.");
  }

  @Test
  @DisplayName("Should answer with the pinned expired body when the device code is unknown")
  void shouldAnswerWithPinnedExpiredBodyWhenDeviceCodeUnknown() throws Exception {
    assertErrorBody(
        pollExpectingBadRequest("not-a-device-code"),
        "expired_token",
        "The device code is unknown or no longer usable.");
  }

  @Test
  @DisplayName("Should answer with the denied terminal body when the pairing request is denied")
  void shouldAnswerWithDeniedTerminalBodyWhenPairingRequestDenied() throws Exception {
    var issued = issueCode("Apple TV");
    var approver = seedAccount();
    decide(approver, issued.get("userCode").asString(), "DENY");

    assertErrorBody(
        pollExpectingBadRequest(issued.get("deviceCode").asString()),
        "access_denied",
        "The device authorization request was denied.");
  }

  @Test
  @DisplayName(
      "Should return the standard tokens response when the winning poll consumes the grant")
  void shouldReturnStandardTokensResponseWhenWinningPollConsumesGrant() throws Exception {
    var issued = issueCode("Apple TV");
    var approver = seedAccount();
    decide(approver, issued.get("userCode").asString(), "APPROVE");

    var body =
        readJson(
            mockMvc
                .perform(pollRequest(issued.get("deviceCode").asString()))
                .andExpect(status().isOk())
                .andExpect(uncacheable()));

    assertThat(fieldNamesOf(body))
        .containsExactlyInAnyOrder("accessToken", "accessTokenExpiresAt", "scope", "refreshToken");
    assertThat(body.get("scope").asString()).isEqualTo("account");
    assertThat(body.get("accessToken").asString()).isNotBlank();
    assertThat(body.get("refreshToken").asString()).isNotBlank();
    assertThat(Instant.parse(body.get("accessTokenExpiresAt").asString())).isAfter(Instant.now());
  }

  @Test
  @DisplayName("Should show the requesting device when a signed-in approver looks up the code")
  void shouldShowRequestingDeviceWhenSignedInApproverLooksUpCode() throws Exception {
    var issued = issueCode("Living Room Apple TV");
    var approver = seedAccount();

    var body =
        readJson(
            mockMvc
                .perform(
                    authenticated(approver, post("/api/auth/device/authorizations/lookup"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userCodeBody(issued.get("userCode").asString())))
                .andExpect(status().isOk())
                .andExpect(uncacheable()));

    assertThat(fieldNamesOf(body))
        .containsExactlyInAnyOrder("userCode", "deviceName", "status", "requestedAt", "households");
    assertThat(body.get("userCode").asString()).isEqualTo(issued.get("userCode").asString());
    assertThat(body.get("deviceName").asString()).isEqualTo("Living Room Apple TV");
    assertThat(body.get("status").asString()).isEqualTo("PENDING");
    assertThat(Instant.parse(body.get("requestedAt").asString())).isBeforeOrEqualTo(Instant.now());
  }

  @Test
  @DisplayName("Should accept a typed code when its display separator is omitted")
  void shouldAcceptTypedCodeWhenDisplaySeparatorOmitted() throws Exception {
    var issued = issueCode("Apple TV");
    var approver = seedAccount();
    var typed = issued.get("userCode").asString().replace("-", "").toLowerCase(Locale.ROOT);

    mockMvc
        .perform(
            authenticated(approver, post("/api/auth/device/authorizations/lookup"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(userCodeBody(typed)))
        .andExpect(status().isOk());
  }

  @ParameterizedTest(name = "Should show {1} after {0}")
  @CsvSource({"APPROVE, APPROVED", "DENY, DENIED"})
  @DisplayName("Should show the decided status when the code is looked up after a decision")
  void shouldShowDecidedStatusWhenCodeLookedUpAfterDecision(String decision, String expectedStatus)
      throws Exception {
    var issued = issueCode("Apple TV");
    var approver = seedAccount();
    decide(approver, issued.get("userCode").asString(), decision);

    var body = lookup(approver, issued.get("userCode").asString());

    assertThat(body.get("status").asString()).isEqualTo(expectedStatus);
  }

  @Test
  @DisplayName("Should show consumed when the approved device has redeemed the grant")
  void shouldShowConsumedWhenApprovedDeviceHasRedeemedGrant() throws Exception {
    var issued = issueCode("Apple TV");
    var approver = seedAccount();
    decide(approver, issued.get("userCode").asString(), "APPROVE");
    mockMvc.perform(pollRequest(issued.get("deviceCode").asString())).andExpect(status().isOk());

    var body = lookup(approver, issued.get("userCode").asString());

    assertThat(body.get("status").asString()).isEqualTo("CONSUMED");
  }

  @ParameterizedTest(name = "Should echo {1} after {0}")
  @CsvSource({"APPROVE, APPROVED", "DENY, DENIED"})
  @DisplayName("Should echo the decision that happened when an approver decides the grant")
  void shouldEchoActualDecisionWhenApproverDecidesGrant(String decision, String expectedStatus)
      throws Exception {
    var issued = issueCode("Apple TV");
    var approver = seedAccount();

    var body = decide(approver, issued.get("userCode").asString(), decision);

    assertThat(fieldNamesOf(body)).containsExactlyInAnyOrder("status", "deviceName");
    assertThat(body.get("status").asString()).isEqualTo(expectedStatus);
    assertThat(body.get("deviceName").asString()).isEqualTo("Apple TV");
  }

  @Test
  @DisplayName("Should reject the request before touching the code when the decision is unknown")
  void shouldRejectRequestBeforeTouchingCodeWhenDecisionUnknown() throws Exception {
    var approver = seedAccount();

    var body =
        readJson(
            mockMvc
                .perform(
                    authenticated(approver, post("/api/auth/device/authorizations/decision"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userCode\": \"BCDF-GHJK\", \"decision\": \"MAYBE\"}"))
                .andExpect(status().isBadRequest()));

    assertErrorBody(body, "INVALID_DECISION", "The decision must be APPROVE or DENY.");
  }

  @Test
  @DisplayName("Should reject the request before touching the code when the decision is missing")
  void shouldRejectRequestBeforeTouchingCodeWhenDecisionMissing() throws Exception {
    var approver = seedAccount();

    var body =
        readJson(
            mockMvc
                .perform(
                    authenticated(approver, post("/api/auth/device/authorizations/decision"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userCode\": \"BCDF-GHJK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(uncacheable()));

    assertErrorBody(body, "INVALID_DECISION", "The decision must be APPROVE or DENY.");
  }

  @Test
  @DisplayName("Should reject the request when the Household identifier is malformed")
  void shouldRejectRequestWhenHouseholdIdentifierMalformed() throws Exception {
    var approver = seedAccount();

    var body =
        readJson(
            mockMvc
                .perform(
                    authenticated(approver, post("/api/auth/device/authorizations/decision"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "userCode": "BCDF-GHJK",
                              "decision": "APPROVE",
                              "householdId": "not-a-uuid"
                            }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(uncacheable()));

    assertErrorBody(body, "INVALID_REQUEST", "The request body is missing or malformed.");
  }

  @Test
  @DisplayName("Should reject with the pinned body when the lookup user code is malformed")
  void shouldRejectWithPinnedBodyWhenLookupUserCodeMalformed() throws Exception {
    var approver = seedAccount();

    var body =
        readJson(
            mockMvc
                .perform(
                    authenticated(approver, post("/api/auth/device/authorizations/lookup"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userCodeBody("NOPE")))
                .andExpect(status().isBadRequest()));

    assertErrorBody(body, "INVALID_USER_CODE", "The user code is not a valid pairing code.");
  }

  @Test
  @DisplayName("Should reject with the pinned body when the decision user code is malformed")
  void shouldRejectWithPinnedBodyWhenDecisionUserCodeMalformed() throws Exception {
    var approver = seedAccount();

    var body =
        readJson(
            mockMvc
                .perform(
                    authenticated(approver, post("/api/auth/device/authorizations/decision"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("NOPE", "APPROVE")))
                .andExpect(status().isBadRequest())
                .andExpect(uncacheable()));

    assertErrorBody(body, "INVALID_USER_CODE", "The user code is not a valid pairing code.");
  }

  @Test
  @DisplayName("Should collapse the result into not-found when lookup receives an unknown code")
  void shouldCollapseResultIntoNotFoundWhenLookupReceivesUnknownCode() throws Exception {
    var approver = seedAccount();

    var body =
        readJson(
            mockMvc
                .perform(
                    authenticated(approver, post("/api/auth/device/authorizations/lookup"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userCodeBody("BCDF-GHJK")))
                .andExpect(status().isNotFound())
                .andExpect(uncacheable()));

    assertErrorBody(body, "DEVICE_CODE_NOT_FOUND", "No pairing request matches that code.");
  }

  @Test
  @DisplayName("Should collapse the result into not-found when decision receives an unknown code")
  void shouldCollapseResultIntoNotFoundWhenDecisionReceivesUnknownCode() throws Exception {
    var approver = seedAccount();

    var body =
        readJson(
            mockMvc
                .perform(
                    authenticated(approver, post("/api/auth/device/authorizations/decision"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody("BCDF-GHJK", "APPROVE")))
                .andExpect(status().isNotFound())
                .andExpect(uncacheable()));

    assertErrorBody(body, "DEVICE_CODE_NOT_FOUND", "No pairing request matches that code.");
  }

  @Test
  @DisplayName("Should return conflict rather than overwrite when a second decision is submitted")
  void shouldReturnConflictRatherThanOverwriteWhenSecondDecisionSubmitted() throws Exception {
    var issued = issueCode("Apple TV");
    var approver = seedAccount();
    decide(approver, issued.get("userCode").asString(), "APPROVE");

    var body =
        readJson(
            mockMvc
                .perform(
                    authenticated(approver, post("/api/auth/device/authorizations/decision"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody(issued.get("userCode").asString(), "DENY")))
                .andExpect(status().isConflict()));

    assertErrorBody(
        body, "DEVICE_CODE_NOT_PENDING", "That pairing request has already been decided.");
  }

  @Test
  @DisplayName("Should answer with the exact approver-facing body when the code has expired")
  void shouldAnswerWithExactApproverFacingBodyWhenCodeExpired() throws Exception {
    var issued = issueCode("Apple TV");
    var authorization =
        authorizationRepository
            .findByUserCode(issued.get("userCode").asString().replace("-", ""))
            .orElseThrow();
    authorization.setExpiresAt(Instant.EPOCH);
    authorizationRepository.saveAndFlush(authorization);
    var approver = seedAccount();

    var body =
        readJson(
            mockMvc
                .perform(
                    authenticated(approver, post("/api/auth/device/authorizations/decision"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionBody(issued.get("userCode").asString(), "APPROVE")))
                .andExpect(status().isBadRequest())
                .andExpect(uncacheable()));

    assertErrorBody(
        body,
        "DEVICE_CODE_EXPIRED",
        "That pairing code has expired; start a new one on the device.");
  }

  @Test
  @DisplayName("Should refuse lookup and decision when the caller is unauthenticated")
  void shouldRefuseLookupAndDecisionWhenCallerUnauthenticated() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/device/authorizations/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(userCodeBody("BCDF-GHJK")))
        .andExpect(status().isUnauthorized());

    mockMvc
        .perform(
            post("/api/auth/device/authorizations/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content(decisionBody("BCDF-GHJK", "APPROVE")))
        .andExpect(status().isUnauthorized());
  }

  private JsonNode issueCode(String deviceName) throws Exception {
    return readJson(
        mockMvc
            .perform(
                post("/api/auth/device/code")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"deviceName\": \"%s\", \"esn\": \"esn-contract\"}"
                            .formatted(deviceName)))
            .andExpect(status().isOk())
            .andExpect(uncacheable()));
  }

  private JsonNode pollExpectingBadRequest(String deviceCode) throws Exception {
    return readJson(
        mockMvc
            .perform(pollRequest(deviceCode))
            .andExpect(status().isBadRequest())
            .andExpect(uncacheable()));
  }

  private static MockHttpServletRequestBuilder pollRequest(String deviceCode) {
    return post("/api/auth/device/token")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"deviceCode\": \"%s\"}".formatted(deviceCode));
  }

  private static MockHttpServletRequestBuilder jsonRequest(
      MockHttpServletRequestBuilder request, String content) {
    request.contentType(MediaType.APPLICATION_JSON);
    if (content == null) {
      return request;
    }

    return request.content(content);
  }

  private JsonNode decide(UserAccount approver, String userCode, String decision) throws Exception {
    // An approval binds the TV to a Household (ADR 0024); the approver's own is the default here.
    var body =
        "APPROVE".equals(decision)
            ? "{\"userCode\": \"%s\", \"decision\": \"%s\", \"householdId\": \"%s\"}"
                .formatted(userCode, decision, approver.getHouseholdId())
            : decisionBody(userCode, decision);
    return readJson(
        mockMvc
            .perform(
                authenticated(approver, post("/api/auth/device/authorizations/decision"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(uncacheable()));
  }

  private JsonNode lookup(UserAccount approver, String userCode) throws Exception {
    return readJson(
        mockMvc
            .perform(
                authenticated(approver, post("/api/auth/device/authorizations/lookup"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(userCodeBody(userCode)))
            .andExpect(status().isOk())
            .andExpect(uncacheable()));
  }

  private MockHttpServletRequestBuilder authenticated(
      UserAccount account, MockHttpServletRequestBuilder request) {
    var session = refreshTokenService.createSession(account, "web").session();
    var accessToken = accessTokenIssuer.issue(TokenContext.of(account, session));
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken.value());
  }

  private UserAccount seedAccount() {
    var account = authTestSupport.createAccount();
    accountIds.add(account.getId());
    return account;
  }

  /**
   * Spring Security's CacheControlHeadersWriter writes this whole set or none of it — it backs off
   * the moment a handler has set any cache header itself. Asserting all three fails loudly if a
   * handler-level header ever downgrades a credential-bearing response to a weaker single one.
   */
  private static ResultMatcher uncacheable() {
    return result -> {
      header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")).match(result);
      header().string(HttpHeaders.PRAGMA, "no-cache").match(result);
      header().string(HttpHeaders.EXPIRES, "0").match(result);
    };
  }

  private static String userCodeBody(String userCode) {
    return "{\"userCode\": \"%s\"}".formatted(userCode);
  }

  private static String decisionBody(String userCode, String decision) {
    return "{\"userCode\": \"%s\", \"decision\": \"%s\"}".formatted(userCode, decision);
  }

  private JsonNode readJson(ResultActions actions) throws Exception {
    return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
  }

  private static Set<String> fieldNamesOf(JsonNode node) {
    return Set.copyOf(node.propertyNames());
  }

  private static void assertErrorBody(JsonNode body, String code, String message) {
    assertThat(fieldNamesOf(body)).containsExactlyInAnyOrder("code", "message");
    assertThat(body.get("code").asString()).isEqualTo(code);
    assertThat(body.get("message").asString()).isEqualTo(message);
  }
}
