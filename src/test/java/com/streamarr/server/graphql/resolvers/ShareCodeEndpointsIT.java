package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.ShareCode.SHARE_CODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.repositories.auth.ShareCodeRepository;
import com.streamarr.server.services.auth.ShareCodes;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
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

  private static final String WITHDRAW =
      """
      mutation Withdraw($shareCodeId: ID!) {
        withdrawShareCode(input: { shareCodeId: $shareCodeId }) {
          shareCode { id status endedAt issuedByDisplayName }
          userErrors { __typename ... on InputMutationError { message inputPath } }
        }
      }
      """;

  private static final String LIST =
      """
      query List($householdId: ID!, $statuses: [ShareCodeStatus!]) {
        householdShareCodes(householdId: $householdId, statuses: $statuses) {
          edges { node { id status expiresAt issuedByAccountId issuedByDisplayName } }
        }
      }
      """;

  private static final String LOOK_UP =
      """
      mutation LookUp($code: String!) {
        lookUpShareCode(input: { code: $code }) {
          householdName
          userErrors { __typename ... on InputMutationError { message inputPath } }
        }
      }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ShareCodeRepository shareCodeRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

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

  @Test
  @DisplayName(
      "Should list who issued each code and when it expires when a HouseholdAdmin lists them")
  void shouldListWhoIssuedEachCodeAndWhenItExpiresWhenHouseholdAdminListsThem() throws Exception {
    var serverAdmin = authTestSupport.createAdminIdentity();
    identities.add(serverAdmin);
    var hosts = issuedId(issue(authTestSupport.accountBearer(host), host.household().getId()));
    var servers =
        issuedId(issue(authTestSupport.accountBearer(serverAdmin), host.household().getId()));

    var listed = list(authTestSupport.accountBearer(host), List.of());

    assertThat(listed)
        .extracting(node -> node.path("id").asString())
        .containsExactly(servers, hosts);
    assertThat(listed)
        .extracting(node -> node.path("issuedByDisplayName").asString())
        .containsExactly(serverAdmin.account().getDisplayName(), host.account().getDisplayName());
    assertThat(listed)
        .allSatisfy(node -> assertThat(node.path("expiresAt").asString()).isNotEmpty());
  }

  @Test
  @DisplayName("Should list nothing when a member or a device-bound session lists the codes")
  void shouldListNothingWhenMemberOrDeviceBoundSessionListsCodes() throws Exception {
    issue(authTestSupport.accountBearer(host), host.household().getId());
    var member = authTestSupport.createHouseholdMemberIdentity(host);

    assertThat(list(authTestSupport.accountBearer(member), List.of())).isEmpty();
    assertThat(list(authTestSupport.deviceBoundProfileBearer(host), List.of())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should take a withdrawn code out of the live list when a HouseholdAdmin withdraws it")
  void shouldTakeWithdrawnCodeOutOfLiveListWhenHouseholdAdminWithdrawsIt() throws Exception {
    var serverAdmin = authTestSupport.createAdminIdentity();
    identities.add(serverAdmin);
    var kept = issuedId(issue(authTestSupport.accountBearer(host), host.household().getId()));
    var withdrawnId =
        issuedId(issue(authTestSupport.accountBearer(serverAdmin), host.household().getId()));

    var payload = withdraw(authTestSupport.accountBearer(host), withdrawnId);

    assertThat(payload.path("userErrors")).isEmpty();
    assertThat(payload.path("shareCode").path("status").asString()).isEqualTo("WITHDRAWN");
    assertThat(payload.path("shareCode").path("endedAt").asString()).isNotEmpty();
    assertThat(list(authTestSupport.accountBearer(host), List.of("LIVE")))
        .extracting(node -> node.path("id").asString())
        .containsExactly(kept);
    assertThat(list(authTestSupport.accountBearer(host), List.of("WITHDRAWN")))
        .extracting(node -> node.path("id").asString())
        .containsExactly(withdrawnId);
  }

  @Test
  @DisplayName("Should refuse as not live when a withdrawn code is withdrawn again")
  void shouldRefuseAsNotLiveWhenWithdrawnCodeIsWithdrawnAgain() throws Exception {
    var id = issuedId(issue(authTestSupport.accountBearer(host), host.household().getId()));
    withdraw(authTestSupport.accountBearer(host), id);

    var again = withdraw(authTestSupport.accountBearer(host), id);

    assertThat(again.path("shareCode").isNull()).isTrue();
    assertThat(again.path("userErrors"))
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.path("__typename").asString()).isEqualTo("ShareCodeNotLiveError");
              assertThat(error.path("inputPath").get(0).asString()).isEqualTo("shareCodeId");
            });
  }

  @Test
  @DisplayName("Should refuse as not found when a member withdraws the Household's code")
  void shouldRefuseAsNotFoundWhenMemberWithdrawsHouseholdsCode() throws Exception {
    var id = issuedId(issue(authTestSupport.accountBearer(host), host.household().getId()));
    var member = authTestSupport.createHouseholdMemberIdentity(host);

    var payload = withdraw(authTestSupport.accountBearer(member), id);

    assertThat(payload.path("userErrors"))
        .singleElement()
        .extracting(error -> error.path("__typename").asString())
        .isEqualTo("ShareCodeNotFoundError");
    assertThat(list(authTestSupport.accountBearer(host), List.of("LIVE")))
        .extracting(node -> node.path("id").asString())
        .containsExactly(id);
  }

  @Test
  @DisplayName(
      "Should name only the Household when someone of another Household looks a live code up")
  void shouldNameOnlyHouseholdWhenSomeoneOfAnotherHouseholdLooksLiveCodeUp() throws Exception {
    var code = issuedCode(issue(authTestSupport.accountBearer(host), host.household().getId()));
    var presenter = identity();

    var payload = lookUp(authTestSupport.accountBearer(presenter), code.toLowerCase(Locale.ROOT));

    assertThat(payload.path("userErrors")).isEmpty();
    assertThat(payload.path("householdName").asString()).isEqualTo(host.household().getName());
  }

  @Test
  @DisplayName("Should refuse in one way naming no Household when the code does not work")
  void shouldRefuseInOneWayNamingNoHouseholdWhenCodeDoesNotWork() throws Exception {
    var presenter = identity();
    var withdrawn = issue(authTestSupport.accountBearer(host), host.household().getId());
    withdraw(authTestSupport.accountBearer(host), issuedId(withdrawn));
    var expired = issue(authTestSupport.accountBearer(host), host.household().getId());
    transactionTemplate.executeWithoutResult(
        _ ->
            dsl.update(SHARE_CODE)
                .set(SHARE_CODE.EXPIRES_AT, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1))
                .where(SHARE_CODE.ID.eq(UUID.fromString(issuedId(expired))))
                .execute());

    var refusals =
        List.of(
            lookUp(authTestSupport.accountBearer(presenter), "BCDF-GHJK"),
            lookUp(authTestSupport.accountBearer(presenter), issuedCode(withdrawn)),
            lookUp(authTestSupport.accountBearer(presenter), issuedCode(expired)));

    assertThat(refusals)
        .allSatisfy(
            payload -> {
              assertThat(payload.path("householdName").isNull()).isTrue();
              assertThat(payload.path("userErrors"))
                  .isEqualTo(refusals.getFirst().path("userErrors"));
            });
    assertThat(refusals.getFirst().path("userErrors"))
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.path("__typename").asString()).isEqualTo("ShareCodeRefusedError");
              assertThat(error.path("message").asString())
                  .doesNotContain(host.household().getName());
              assertThat(error.path("inputPath").get(0).asString()).isEqualTo("code");
            });
  }

  @Test
  @DisplayName("Should keep earlier failures when a lookup succeeds between wrong codes")
  void shouldKeepEarlierFailuresWhenLookupSucceedsBetweenWrongCodes() throws Exception {
    var code = issuedCode(issue(authTestSupport.accountBearer(host), host.household().getId()));
    var presenter = authTestSupport.accountBearer(identity());
    for (var failure = 0; failure < 4; failure++) {
      assertThat(lookUp(presenter, "BCDF-GHJK").path("userErrors")).hasSize(1);
    }

    assertThat(lookUp(presenter, code).path("householdName").asString())
        .isEqualTo(host.household().getName());
    assertThat(lookUp(presenter, "BCDF-GHJK").path("userErrors")).hasSize(1);

    var throttled = errors(executeAllowingErrors(presenter, LOOK_UP, Map.of("code", code)));
    assertThat(throttled.path("extensions").path("code").asString())
        .isEqualTo("TOO_MANY_CREDENTIAL_ATTEMPTS");
    assertThat(throttled.path("extensions").path("retryAfterSeconds").asLong()).isPositive();
  }

  @Test
  @DisplayName("Should forbid the lookup when a device-bound session presents a code")
  void shouldForbidLookupWhenDeviceBoundSessionPresentsCode() throws Exception {
    var code = issuedCode(issue(authTestSupport.accountBearer(host), host.household().getId()));
    var presenter = identity();

    var forbidden =
        errors(
            executeAllowingErrors(
                authTestSupport.deviceBoundProfileBearer(presenter),
                LOOK_UP,
                Map.of("code", code)));

    assertThat(forbidden.path("extensions").path("code").asString()).isEqualTo("FORBIDDEN");
  }

  private static String issuedCode(JsonNode payload) {
    return payload.path("issued").path("code").asString();
  }

  private JsonNode lookUp(String bearer, String code) throws Exception {
    return execute(bearer, LOOK_UP, Map.of("code", code)).path("data").path("lookUpShareCode");
  }

  private static JsonNode errors(JsonNode body) {
    assertThat(body.path("errors")).hasSize(1);
    return body.path("errors").get(0);
  }

  private static String issuedId(JsonNode payload) {
    return payload.path("issued").path("shareCode").path("id").asString();
  }

  private JsonNode withdraw(String bearer, String shareCodeId) throws Exception {
    return execute(bearer, WITHDRAW, Map.of("shareCodeId", shareCodeId))
        .path("data")
        .path("withdrawShareCode");
  }

  private List<JsonNode> list(String bearer, List<String> statuses) throws Exception {
    var nodes = new ArrayList<JsonNode>();
    execute(
            bearer,
            LIST,
            Map.of("householdId", host.household().getId().toString(), "statuses", statuses))
        .path("data")
        .path("householdShareCodes")
        .path("edges")
        .forEach(edge -> nodes.add(edge.path("node")));
    return nodes;
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
    var body = executeAllowingErrors(bearer, document, variables);
    assertThat(body.path("errors").isMissingNode())
        .as("errors in %s", body.path("errors"))
        .isTrue();
    return body;
  }

  private JsonNode executeAllowingErrors(
      String bearer, String document, Map<String, Object> variables) throws Exception {
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
    return objectMapper.readTree(response);
  }

  private AuthTestSupport.TestIdentity identity() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);
    return identity;
  }
}
