package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.repositories.auth.ShareCodeRepository;
import com.streamarr.server.services.auth.ShareCodes;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Share codes through GraphQL: who may issue one, and what the answer and the list carry. */
@Tag("IntegrationTest")
@DisplayName("Share Code Endpoints Integration Tests")
class ShareCodeEndpointsIT extends AbstractIntegrationTest {

  private static final String ISSUE =
      """
      mutation Issue($householdId: ID!) {
        issueShareCode(input: { householdId: $householdId }) {
          issued {
            code
            shareCode {
              id householdId status issuedAt expiresAt endedAt
              issuedByAccountId issuedByDisplayName
            }
          }
          userErrors { __typename ... on InputMutationError { message inputPath } }
        }
      }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ShareCodeRepository shareCodeRepository;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUp() {
    host = identity();
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should answer with a live code once when a HouseholdAdmin issues one")
  void shouldAnswerWithLiveCodeOnceWhenHouseholdAdminIssuesOne() throws Exception {
    var payload = issue(authTestSupport.accountBearer(host), host.household().getId());

    var issued = payload.path("issued");
    var code = issued.path("code").asString();
    var shareCode = issued.path("shareCode");
    assertThat(payload.path("userErrors")).isEmpty();
    assertThat(code).matches("[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}");
    assertThat(shareCode.path("householdId").asString())
        .isEqualTo(host.household().getId().toString());
    assertThat(shareCode.path("status").asString()).isEqualTo("LIVE");
    assertThat(shareCode.path("endedAt").isNull()).isTrue();
    assertThat(shareCode.path("issuedByAccountId").asString())
        .isEqualTo(host.account().getId().toString());
    assertThat(shareCode.path("issuedByDisplayName").asString())
        .isEqualTo(host.account().getDisplayName());
    assertThat(
            Duration.between(
                Instant.parse(shareCode.path("issuedAt").asString()),
                Instant.parse(shareCode.path("expiresAt").asString())))
        .isBetween(Duration.ofDays(7), Duration.ofDays(7).plusMinutes(1));

    var stored =
        shareCodeRepository
            .findById(UUID.fromString(shareCode.path("id").asString()))
            .orElseThrow();
    assertThat(stored.getCodeDigest())
        .isEqualTo(new ShareCodes().digestOf(code).orElseThrow())
        .hasSize(32);
  }

  @Test
  @DisplayName("Should issue a code for any Household when a ServerAdmin issues one")
  void shouldIssueCodeForAnyHouseholdWhenServerAdminIssuesOne() throws Exception {
    var serverAdmin = authTestSupport.createAdminIdentity();
    identities.add(serverAdmin);

    var payload = issue(authTestSupport.accountBearer(serverAdmin), host.household().getId());

    assertThat(payload.path("userErrors")).isEmpty();
    assertThat(payload.path("issued").path("shareCode").path("issuedByDisplayName").asString())
        .isEqualTo(serverAdmin.account().getDisplayName());
  }

  @Test
  @DisplayName("Should refuse as not found when a member of the Household issues a code")
  void shouldRefuseAsNotFoundWhenMemberOfHouseholdIssuesCode() throws Exception {
    var member = authTestSupport.createHouseholdMemberIdentity(host);

    assertHouseholdNotFound(issue(authTestSupport.accountBearer(member), host.household().getId()));
  }

  @Test
  @DisplayName("Should refuse as not found when a HouseholdAdmin of another Household issues one")
  void shouldRefuseAsNotFoundWhenHouseholdAdminOfAnotherHouseholdIssuesOne() throws Exception {
    var stranger = identity();

    assertHouseholdNotFound(
        issue(authTestSupport.accountBearer(stranger), host.household().getId()));
  }

  @Test
  @DisplayName("Should refuse as not found when a device-bound session issues a code")
  void shouldRefuseAsNotFoundWhenDeviceBoundSessionIssuesCode() throws Exception {
    assertHouseholdNotFound(
        issue(authTestSupport.deviceBoundProfileBearer(host), host.household().getId()));
  }

  @Test
  @DisplayName("Should refuse as not found when the Household does not exist")
  void shouldRefuseAsNotFoundWhenHouseholdDoesNotExist() throws Exception {
    var serverAdmin = authTestSupport.createAdminIdentity();
    identities.add(serverAdmin);

    assertHouseholdNotFound(issue(authTestSupport.accountBearer(serverAdmin), UUID.randomUUID()));
  }

  private void assertHouseholdNotFound(JsonNode payload) {
    assertThat(payload.path("issued").isNull()).isTrue();
    assertThat(payload.path("userErrors"))
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.path("__typename").asString()).isEqualTo("HouseholdNotFoundError");
              assertThat(error.path("inputPath").get(0).asString()).isEqualTo("householdId");
            });
    assertThat(shareCodeRepository.findAll())
        .noneMatch(shareCode -> shareCode.getHouseholdId().equals(host.household().getId()));
  }

  private JsonNode issue(String bearer, UUID householdId) throws Exception {
    return execute(bearer, ISSUE, Map.of("householdId", householdId.toString()))
        .path("data")
        .path("issueShareCode");
  }

  private JsonNode execute(String bearer, String document, Map<String, Object> variables)
      throws Exception {
    var response =
        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of("query", document, "variables", variables))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var body = objectMapper.readTree(response);
    assertThat(body.path("errors").isMissingNode())
        .as("errors in %s", body.path("errors"))
        .isTrue();
    return body;
  }

  private AuthTestSupport.TestIdentity identity() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);
    return identity;
  }
}
