package com.streamarr.server.controllers.auth.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccessTokenIssuer;
import com.streamarr.server.services.auth.RefreshTokenService;
import com.streamarr.server.services.auth.TokenContext;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins {@code /api/auth/device/**} and the status extension against the golden fixtures in {@code
 * docs/contracts/device-pairing/v1}. Poll-state bodies are asserted byte-for-byte: clients branch
 * on those exact lowercase codes.
 */
@Tag("IntegrationTest")
@DisplayName("Device Auth Contract Integration Tests")
class DeviceAuthContractIT extends AbstractIntegrationTest {

  private static final Path FIXTURES = Path.of("docs/contracts/device-pairing/v1");

  @Autowired private MockMvc mockMvc;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private DeviceAuthorizationRepository authorizationRepository;

  @Autowired private AccessTokenIssuer accessTokenIssuer;

  @Autowired private RefreshTokenService refreshTokenService;

  @Autowired private AuthSessionRepository sessionRepository;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private ObjectMapper objectMapper;

  private final List<UUID> accountIds = new ArrayList<>();

  private TestIdentity identity;

  @AfterEach
  void deleteSeededRows() {
    authorizationRepository.deleteAll();
    accountIds.forEach(userAccountRepository::deleteById);
    accountIds.clear();
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
      identity = null;
    }
  }

  @Test
  @DisplayName("Should report both required status flags")
  void shouldReportBothRequiredStatusFlags() throws Exception {
    var body =
        readJson(
            mockMvc
                .perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(uncacheable()));

    assertThat(fieldNamesOf(body)).isEqualTo(fieldNamesOf(fixture("status.json")));
    assertThat(body.get("devicePairingEnabled").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("Should issue an absolute verification URI and the server's own timings")
  void shouldIssueAbsoluteVerificationUriAndServersOwnTimings() throws Exception {
    var body = issueCode("Apple TV");

    assertThat(fieldNamesOf(body)).isEqualTo(fieldNamesOf(fixture("code-success.json")));
    assertThat(body.get("verificationUri").asString()).isEqualTo("https://home.example.test/link");
    assertThat(body.get("userCode").asString())
        .matches("[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}");
    assertThat(body.get("deviceCode").asString()).hasSize(43);
    assertThat(body.get("interval").asInt()).isEqualTo(5);
    assertThat(body.get("expiresIn").asLong()).isEqualTo(600);
  }

  @Test
  @DisplayName("Should issue with the fallback device name when the body is absent")
  void shouldIssueWithFallbackDeviceNameWhenBodyAbsent() throws Exception {
    var body =
        readJson(
            mockMvc
                .perform(post("/api/auth/device/code"))
                .andExpect(status().isOk())
                .andExpect(uncacheable()));

    assertThat(body.get("deviceCode").asString()).hasSize(43);
    assertThat(authorizationRepository.findAll())
        .singleElement()
        .satisfies(row -> assertThat(row.getDeviceName()).isEqualTo("Unknown device"));
  }

  @Test
  @DisplayName("Should answer an un-approved poll with the pinned pending body on HTTP 400")
  void shouldAnswerUnapprovedPollWithPinnedPendingBodyOnBadRequest() throws Exception {
    var deviceCode = issueCode("Apple TV").get("deviceCode").asString();

    // RFC 8628 §3.2: the interval is the wait between polls, so the first one is never too soon.
    assertThat(pollExpectingBadRequest(deviceCode))
        .isEqualTo(fixture("authorization-pending-error.json"));
  }

  @Test
  @DisplayName("Should answer a too-soon poll with the pinned slow-down body on HTTP 400")
  void shouldAnswerTooSoonPollWithPinnedSlowDownBodyOnBadRequest() throws Exception {
    var deviceCode = issueCode("Apple TV").get("deviceCode").asString();
    pollExpectingBadRequest(deviceCode);

    // The second poll lands well inside the interval the first one started.
    assertThat(pollExpectingBadRequest(deviceCode)).isEqualTo(fixture("slow-down-error.json"));
  }

  @Test
  @DisplayName("Should answer an unknown device code with the pinned expired body")
  void shouldAnswerUnknownDeviceCodeWithPinnedExpiredBody() throws Exception {
    assertThat(pollExpectingBadRequest("not-a-device-code"))
        .isEqualTo(fixture("expired-token-error.json"));
  }

  @Test
  @DisplayName("Should answer a denied request with its own terminal body, never a 403")
  void shouldAnswerDeniedRequestWithItsOwnTerminalBodyNeverForbidden() throws Exception {
    var issued = issueCode("Apple TV");
    var approver = seedAccount();
    decide(approver, issued.get("userCode").asString(), "DENY");

    assertThat(pollExpectingBadRequest(issued.get("deviceCode").asString()))
        .isEqualTo(fixture("access-denied-error.json"));
  }

  @Test
  @DisplayName("Should return the standard tokens response to the winning poll")
  void shouldReturnStandardTokensResponseToWinningPoll() throws Exception {
    var issued = issueCode("Apple TV");
    var approver = seedAccount();
    decide(approver, issued.get("userCode").asString(), "APPROVE");

    var body =
        readJson(
            mockMvc
                .perform(pollRequest(issued.get("deviceCode").asString()))
                .andExpect(status().isOk())
                .andExpect(uncacheable()));

    assertThat(fieldNamesOf(body)).isEqualTo(fieldNamesOf(fixture("token-success.json")));
    assertThat(body.get("scope").asString()).isEqualTo("account");
    assertThat(body.get("refreshToken").asString()).isNotBlank();
  }

  /**
   * The approver device pairing actually meets: one household, one profile. Auto-selection returns
   * early for anyone else, so a bare account never reaches the session's scope write and cannot
   * prove the poll completes.
   */
  @Test
  @DisplayName("Should sign the device in when the approver has one household and one profile")
  void shouldSignDeviceInWhenApproverHasOneHouseholdAndOneProfile() throws Exception {
    var issued = issueCode("Apple TV");
    identity = authTestSupport.createIdentity();
    decide(identity.account(), issued.get("userCode").asString(), "APPROVE");

    var body =
        readJson(
            mockMvc
                .perform(pollRequest(issued.get("deviceCode").asString()))
                .andExpect(status().isOk()));

    assertThat(body.get("scope").asString()).isEqualTo("profile");
    assertThat(body.get("accessToken").asString()).isNotBlank();
    assertThat(body.get("refreshToken").asString()).isNotBlank();
    assertThat(pairedSession()).satisfies(this::carriesTheAutoSelectedScope);

    // The grant is spent: a device that already has tokens must never be able to mint a second set.
    assertThat(pollExpectingBadRequest(issued.get("deviceCode").asString()))
        .isEqualTo(fixture("expired-token-error.json"));
  }

  @Test
  @DisplayName("Should show the requesting device to a signed-in approver")
  void shouldShowRequestingDeviceToSignedInApprover() throws Exception {
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

    assertThat(fieldNamesOf(body)).isEqualTo(fieldNamesOf(fixture("lookup-success.json")));
    assertThat(body.get("deviceName").asString()).isEqualTo("Living Room Apple TV");
    assertThat(body.get("status").asString()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("Should accept a typed code without its separator")
  void shouldAcceptTypedCodeWithoutItsSeparator() throws Exception {
    var issued = issueCode("Apple TV");
    var approver = seedAccount();
    var typed =
        issued.get("userCode").asString().replace("-", "").toLowerCase(java.util.Locale.ROOT);

    mockMvc
        .perform(
            authenticated(approver, post("/api/auth/device/authorizations/lookup"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(userCodeBody(typed)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should echo the decision that actually happened")
  void shouldEchoDecisionThatActuallyHappened() throws Exception {
    var issued = issueCode("Apple TV");
    var approver = seedAccount();

    var body = decide(approver, issued.get("userCode").asString(), "DENY");

    assertThat(fieldNamesOf(body)).isEqualTo(fieldNamesOf(fixture("decision-success.json")));
    assertThat(body.get("status").asString()).isEqualTo("DENIED");
  }

  @Test
  @DisplayName("Should reject an unknown decision value before touching the code")
  void shouldRejectUnknownDecisionValueBeforeTouchingCode() throws Exception {
    var approver = seedAccount();

    var body =
        readJson(
            mockMvc
                .perform(
                    authenticated(approver, post("/api/auth/device/authorizations/decision"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userCode\": \"BCDF-GHJK\", \"decision\": \"MAYBE\"}"))
                .andExpect(status().isBadRequest()));

    assertThat(body).isEqualTo(fixture("invalid-decision-error.json"));
  }

  @Test
  @DisplayName("Should reject a malformed user code with the pinned body")
  void shouldRejectMalformedUserCodeWithPinnedBody() throws Exception {
    var approver = seedAccount();

    var body =
        readJson(
            mockMvc
                .perform(
                    authenticated(approver, post("/api/auth/device/authorizations/lookup"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userCodeBody("NOPE")))
                .andExpect(status().isBadRequest()));

    assertThat(body).isEqualTo(fixture("invalid-user-code-error.json"));
  }

  @Test
  @DisplayName("Should collapse an unknown code into not-found on lookup")
  void shouldCollapseUnknownCodeIntoNotFoundOnLookup() throws Exception {
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

    assertThat(body).isEqualTo(fixture("not-found-error.json"));
  }

  @Test
  @DisplayName("Should refuse a second decision with a conflict rather than a silent overwrite")
  void shouldRefuseSecondDecisionWithConflictRatherThanSilentOverwrite() throws Exception {
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

    assertThat(body).isEqualTo(fixture("not-pending-error.json"));
  }

  @Test
  @DisplayName("Should answer an expired decision with its exact approver-facing body")
  void shouldAnswerExpiredDecisionWithExactApproverFacingBody() throws Exception {
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

    assertThat(body).isEqualTo(fixture("expired-error.json"));
  }

  @Test
  @DisplayName("Should refuse lookup and decision to an unauthenticated caller")
  void shouldRefuseLookupAndDecisionToUnauthenticatedCaller() throws Exception {
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
                    .content("{\"deviceName\": \"%s\"}".formatted(deviceName)))
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

  private JsonNode decide(UserAccount approver, String userCode, String decision) throws Exception {
    return readJson(
        mockMvc
            .perform(
                authenticated(approver, post("/api/auth/device/authorizations/decision"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(decisionBody(userCode, decision)))
            .andExpect(status().isOk())
            .andExpect(uncacheable()));
  }

  private MockHttpServletRequestBuilder authenticated(
      UserAccount account, MockHttpServletRequestBuilder request) {
    var session = refreshTokenService.createSession(account, "web").session();
    var accessToken =
        accessTokenIssuer.issue(TokenContext.builder().account(account).session(session).build());
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken.value());
  }

  private AuthSession pairedSession() {
    return sessionRepository.findByAccountId(identity.account().getId()).stream()
        .filter(session -> "Apple TV".equals(session.getDeviceName()))
        .reduce((first, second) -> fail("The poll minted more than one session"))
        .orElseGet(() -> fail("The poll minted no session"));
  }

  private void carriesTheAutoSelectedScope(AuthSession session) {
    assertThat(session.getActiveHouseholdId()).isEqualTo(identity.household().getId());
    assertThat(session.getActiveProfileId()).isEqualTo(identity.profile().getId());
  }

  private UserAccount seedAccount() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
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

  private JsonNode readJson(org.springframework.test.web.servlet.ResultActions actions)
      throws Exception {
    return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
  }

  private static Set<String> fieldNamesOf(JsonNode node) {
    return Set.copyOf(node.propertyNames());
  }

  private JsonNode fixture(String fixtureName) {
    try {
      return objectMapper.readTree(Files.readString(FIXTURES.resolve(fixtureName)));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Missing contract fixture: " + fixtureName, e);
    }
  }
}
