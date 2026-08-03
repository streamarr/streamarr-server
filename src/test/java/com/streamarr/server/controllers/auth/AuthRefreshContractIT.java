package com.streamarr.server.controllers.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.RefreshProposalFixture;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.RefreshTokenService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins {@code POST /api/auth/refresh} against the golden fixtures in {@code
 * docs/contracts/auth-refresh/v1}. Success fixtures pin the field set; error fixtures are asserted
 * byte-for-byte, because clients branch on those bodies.
 */
@Tag("IntegrationTest")
@DisplayName("Auth Refresh Contract Integration Tests")
class AuthRefreshContractIT extends AbstractIntegrationTest {

  private static final Path FIXTURES = Path.of("docs/contracts/auth-refresh/v1");

  @Autowired private MockMvc mockMvc;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private RefreshTokenService refreshTokenService;

  @Autowired private ObjectMapper objectMapper;

  private final List<UUID> accountIds = new ArrayList<>();

  @AfterEach
  void deleteAccountsAndCascades() {
    accountIds.forEach(userAccountRepository::deleteById);
    accountIds.clear();
  }

  @Test
  @DisplayName("Should return the rotation shape with the client's proposal echoed back")
  void shouldReturnRotationShapeWithClientProposalEchoedBack() throws Exception {
    var refreshToken = issueRefreshToken();
    var proposal = RefreshProposalFixture.proposal();

    var body = refreshExpectingOk(refreshToken, proposal);

    assertThat(fieldNamesOf(body)).isEqualTo(fixtureFieldNames("rotation-success.json"));
    assertThat(body.get("refreshToken").asString()).isEqualTo(proposal);
    assertThat(body.get("scope").asString()).isEqualTo("account");
    assertThat(body.get("accessToken").asString()).isNotBlank();
  }

  @Test
  @DisplayName("Should return the recovery shape with the same successor when the pair repeats")
  void shouldReturnRecoveryShapeWithSameSuccessorWhenPairRepeats() throws Exception {
    var refreshToken = issueRefreshToken();
    var proposal = RefreshProposalFixture.proposal();
    var rotation = refreshExpectingOk(refreshToken, proposal);

    var recovery = refreshExpectingOk(refreshToken, proposal);

    assertThat(fieldNamesOf(recovery)).isEqualTo(fixtureFieldNames("recovery-success.json"));
    assertThat(recovery.get("refreshToken").asString()).isEqualTo(proposal);
    assertThat(recovery.get("accessToken").asString())
        .isNotBlank()
        .isNotEqualTo(rotation.get("accessToken").asString());
  }

  @Test
  @DisplayName("Should reject a malformed proposal with the pinned proposal-error body")
  void shouldRejectMalformedProposalWithPinnedProposalErrorBody() throws Exception {
    var refreshToken = issueRefreshToken();

    var body =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(bearerBody(refreshToken, "not-a-canonical-successor")))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(objectMapper.readTree(body)).isEqualTo(fixture("invalid-proposal-error.json"));
  }

  @Test
  @DisplayName("Should reject a proposal sent in cookie mode with the pinned proposal-error body")
  void shouldRejectProposalSentInCookieModeWithPinnedProposalErrorBody() throws Exception {
    var body =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"proposedRefreshToken\": \"%s\"}"
                            .formatted(RefreshProposalFixture.proposal())))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(objectMapper.readTree(body)).isEqualTo(fixture("invalid-proposal-error.json"));
  }

  @Test
  @DisplayName("Should return one constant body for every terminal refresh failure")
  void shouldReturnOneConstantBodyForEveryTerminalRefreshFailure() throws Exception {
    var unknown = refreshExpectingUnauthorized(RefreshProposalFixture.proposal());

    var refreshToken = issueRefreshToken();
    var proposal = RefreshProposalFixture.proposal();
    refreshExpectingOk(refreshToken, proposal);
    refreshExpectingOk(proposal, RefreshProposalFixture.proposal());
    var exactButStale = refreshExpectingUnauthorized(refreshToken, proposal);

    var stolen = issueRefreshToken();
    refreshExpectingOk(stolen, RefreshProposalFixture.proposal());
    var reuseRevoked = refreshExpectingUnauthorized(stolen, RefreshProposalFixture.proposal());

    var expected = fixture("invalid-refresh-token-error.json");
    assertThat(objectMapper.readTree(unknown)).isEqualTo(expected);
    assertThat(objectMapper.readTree(exactButStale)).isEqualTo(expected);
    assertThat(objectMapper.readTree(reuseRevoked)).isEqualTo(expected);
  }

  @Test
  @DisplayName("Should send the bearer request shape the contract fixture documents")
  void shouldSendBearerRequestShapeContractFixtureDocuments() throws Exception {
    assertThat(fieldNamesOf(fixture("bearer-rotation-request.json")))
        .containsExactly("refreshToken", "proposedRefreshToken");
  }

  private JsonNode refreshExpectingOk(String refreshToken, String proposal) throws Exception {
    var body =
        mockMvc
            .perform(
                post("/api/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(bearerBody(refreshToken, proposal)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body);
  }

  private String refreshExpectingUnauthorized(String proposal) throws Exception {
    return refreshExpectingUnauthorized(RefreshProposalFixture.proposal(), proposal);
  }

  private String refreshExpectingUnauthorized(String refreshToken, String proposal)
      throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bearerBody(refreshToken, proposal)))
        .andExpect(status().isUnauthorized())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String issueRefreshToken() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    accountIds.add(account.getId());
    return refreshTokenService.createSession(account, "contract-device").rawToken();
  }

  private static String bearerBody(String refreshToken, String proposal) {
    return "{\"refreshToken\": \"%s\", \"proposedRefreshToken\": \"%s\"}"
        .formatted(refreshToken, proposal);
  }

  private List<String> fixtureFieldNames(String fixtureName) {
    return fieldNamesOf(fixture(fixtureName));
  }

  private static List<String> fieldNamesOf(JsonNode node) {
    return new ArrayList<>(node.propertyNames());
  }

  private JsonNode fixture(String fixtureName) {
    try {
      return objectMapper.readTree(Files.readString(FIXTURES.resolve(fixtureName)));
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Missing contract fixture: " + fixtureName, e);
    }
  }
}
