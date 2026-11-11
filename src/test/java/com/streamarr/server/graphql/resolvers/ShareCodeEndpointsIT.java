package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.ShareCode.SHARE_CODE;
import static com.streamarr.server.support.ServerSettingsTestSupport.limitShareCodes;
import static com.streamarr.server.support.ServerSettingsTestSupport.resetServerSettings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.jooq.generated.enums.ShareCodeStatus;
import com.streamarr.server.repositories.auth.ShareCodeRepository;
import com.streamarr.server.services.auth.ShareCodes;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.LogCapture;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
              id householdId status issuedAt expiresAt endedAt issuedByDisplayName
            }
          }
          userErrors {
            __typename
            ... on MutationError { message }
            ... on InputMutationError { inputPath }
          }
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
      query List($householdId: ID!) {
        householdShareCodes(householdId: $householdId) {
          edges { node { id status expiresAt issuedByDisplayName } }
        }
      }
      """;

  private static final String READ =
      """
      query Read($shareCodeId: ID!) {
        shareCode(shareCodeId: $shareCodeId) {
          id householdId status issuedAt expiresAt endedAt issuedByDisplayName
        }
      }
      """;

  private static final String PAGE =
      """
      query Page($householdId: ID!, $first: Int, $after: String) {
        householdShareCodes(householdId: $householdId, first: $first, after: $after) {
          edges { cursor node { id } }
          pageInfo { hasNextPage }
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

  private static final String OFFER =
      """
      mutation Offer($profileId: ID!, $shareCode: String!) {
        offerProfileShare(input: { profileId: $profileId, shareCode: $shareCode }) {
          share { id }
          userErrors { __typename }
        }
      }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ShareCodeRepository shareCodeRepository;
  @Autowired private ShareCodes shareCodes;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUp() {
    resetServerSettings(dsl);
    host = identity();
  }

  @AfterEach
  void tearDown() {
    resetServerSettings(dsl);
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should ask to withdraw a code when the Household holds the server's limit of live codes")
  void shouldAskToWithdrawACodeWhenHouseholdHoldsTheServersLimitOfLiveCodes() throws Exception {
    limitShareCodes(dsl, 1);
    var bearer = authTestSupport.accountBearer(host);
    issue(bearer, host.household().getId());

    var refused = issue(bearer, host.household().getId());

    assertThat(refused.path("issued").isNull()).isTrue();
    assertThat(refused.path("userErrors")).hasSize(1);
    assertThat(refused.path("userErrors").path(0).path("__typename").asString())
        .isEqualTo("ShareCodeLimitReachedError");
    assertThat(refused.path("userErrors").path(0).path("message").asString())
        .isEqualTo("Withdraw a code before issuing another.");
    assertThat(liveCodesOf(host.household().getId())).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should keep every live code and refuse the next when a ServerAdmin lowers the limit below"
          + " them")
  void shouldKeepEveryLiveCodeAndRefuseTheNextWhenServerAdminLowersTheLimitBelowThem()
      throws Exception {
    var bearer = authTestSupport.accountBearer(host);
    var householdId = host.household().getId();
    issue(bearer, householdId);
    issue(bearer, householdId);
    var serverAdmin = authTestSupport.createAdminIdentity();
    identities.add(serverAdmin);
    execute(
        authTestSupport.accountBearer(serverAdmin),
        "mutation { changeShareCodeLimit(input: {codes: 1}) { userErrors { __typename } } }",
        Map.of());

    var refused = issue(bearer, householdId);

    assertThat(refused.path("userErrors").path(0).path("__typename").asString())
        .isEqualTo("ShareCodeLimitReachedError");
    assertThat(list(bearer))
        .hasSize(2)
        .allSatisfy(node -> assertThat(node.path("status").asString()).isEqualTo("LIVE"));
  }

  @Test
  @DisplayName(
      "Should issue a code that lasts the new lifetime when a ServerAdmin changed it before")
  void shouldIssueCodeThatLastsTheNewLifetimeWhenServerAdminChangedItBefore() throws Exception {
    var serverAdmin = authTestSupport.createAdminIdentity();
    identities.add(serverAdmin);
    execute(
        authTestSupport.accountBearer(serverAdmin),
        "mutation { changeShareCodeLifetime(input: {hours: 2}) { userErrors { __typename } } }",
        Map.of());

    var shareCode =
        issue(authTestSupport.accountBearer(host), host.household().getId())
            .path("issued")
            .path("shareCode");

    assertThat(
            Duration.between(
                Instant.parse(shareCode.path("issuedAt").asString()),
                Instant.parse(shareCode.path("expiresAt").asString())))
        .isBetween(Duration.ofHours(2), Duration.ofHours(2).plusMinutes(1));
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
        .isEqualTo(shareCodes.digestOf(code).orElseThrow())
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

    var listed = list(authTestSupport.accountBearer(host));

    assertThat(listed)
        .extracting(node -> node.path("id").asString())
        .containsExactly(servers, hosts);
    assertThat(listed)
        .extracting(node -> node.path("issuedByDisplayName").asString())
        .containsExactly(serverAdmin.account().getDisplayName(), host.account().getDisplayName());
    assertThat(listed)
        .allSatisfy(node -> assertThat(node.path("expiresAt").asString()).isNotEmpty());
  }

  @ParameterizedTest
  @EnumSource(ShareCodeStatus.class)
  @DisplayName("Should read a code's current status when its HouseholdAdmin asks by id")
  void shouldReadCodesCurrentStatusWhenItsHouseholdAdminAsksById(ShareCodeStatus codeStatus)
      throws Exception {
    var bearer = authTestSupport.accountBearer(host);
    var issued = issue(bearer, host.household().getId());
    var id = issuedId(issued);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var expired = codeStatus == ShareCodeStatus.EXPIRED;
    var storedStatus = expired ? ShareCodeStatus.LIVE : codeStatus;
    var endedAt = storedStatus == ShareCodeStatus.LIVE ? null : now;
    transactionTemplate.executeWithoutResult(
        _ ->
            dsl.update(SHARE_CODE)
                .set(SHARE_CODE.STATUS, storedStatus)
                .set(SHARE_CODE.EXPIRES_AT, expired ? now.minusSeconds(1) : now.plusDays(1))
                .set(SHARE_CODE.ENDED_AT, endedAt)
                .where(SHARE_CODE.ID.eq(UUID.fromString(id)))
                .execute());

    var read = readCode(bearer, id);

    assertThat(read.path("id").asString()).isEqualTo(id);
    assertThat(read.path("householdId").asString()).isEqualTo(host.household().getId().toString());
    assertThat(read.path("status").asString()).isEqualTo(codeStatus.name());
    assertThat(read.path("issuedByDisplayName").asString())
        .isEqualTo(host.account().getDisplayName());
    assertThat(read.toString()).doesNotContain(issuedCode(issued));
    if (codeStatus != ShareCodeStatus.LIVE) {
      assertThat(list(bearer)).isEmpty();
    }
  }

  @Test
  @DisplayName("Should hide a code by id when the caller cannot view its Household's codes")
  void shouldHideCodeByIdWhenCallerCannotViewItsHouseholdsCodes() throws Exception {
    var id = issuedId(issue(authTestSupport.accountBearer(host), host.household().getId()));
    var member = authTestSupport.createHouseholdMemberIdentity(host);
    var stranger = identity();

    assertThat(readCode(authTestSupport.accountBearer(member), id).isNull()).isTrue();
    assertThat(readCode(authTestSupport.accountBearer(stranger), id).isNull()).isTrue();
    assertThat(readCode(authTestSupport.deviceBoundProfileBearer(host), id).isNull()).isTrue();
    assertThat(readCode(authTestSupport.accountBearer(host), UUID.randomUUID().toString()).isNull())
        .isTrue();
  }

  @Test
  @DisplayName("Should read a withdrawn code by id when a ServerAdmin asks for another Household")
  void shouldReadWithdrawnCodeByIdWhenServerAdminAsksForAnotherHousehold() throws Exception {
    var bearer = authTestSupport.accountBearer(host);
    var id = issuedId(issue(bearer, host.household().getId()));
    withdraw(bearer, id);
    var serverAdmin = authTestSupport.createAdminIdentity();
    identities.add(serverAdmin);

    var read = readCode(authTestSupport.accountBearer(serverAdmin), id);

    assertThat(read.path("status").asString()).isEqualTo("WITHDRAWN");
    assertThat(read.path("endedAt").asString()).isNotEmpty();
  }

  @Test
  @DisplayName("Should name no Account for an issuer when a HouseholdAdmin asks the list for one")
  void shouldNameNoAccountForIssuerWhenHouseholdAdminAsksListForOne() throws Exception {
    issue(authTestSupport.accountBearer(host), host.household().getId());

    var body =
        executeAllowingErrors(
            authTestSupport.accountBearer(host),
            """
            query List($householdId: ID!) {
              householdShareCodes(householdId: $householdId) {
                edges { node { issuedByAccountId } }
              }
            }
            """,
            Map.of("householdId", host.household().getId().toString()));

    assertThat(errors(body).path("message").asString()).contains("issuedByAccountId");
    assertThat(body.path("data").path("householdShareCodes").isMissingNode()).isTrue();
  }

  @Test
  @DisplayName("Should list nothing when a member or a device-bound session lists the codes")
  void shouldListNothingWhenMemberOrDeviceBoundSessionListsCodes() throws Exception {
    issue(authTestSupport.accountBearer(host), host.household().getId());
    var member = authTestSupport.createHouseholdMemberIdentity(host);

    assertThat(list(authTestSupport.accountBearer(member))).isEmpty();
    assertThat(list(authTestSupport.deviceBoundProfileBearer(host))).isEmpty();
  }

  @Test
  @DisplayName("Should return the next code when an after cursor is supplied")
  void shouldReturnNextCodeWhenAfterCursorIsSupplied() throws Exception {
    var bearer = authTestSupport.accountBearer(host);
    issue(bearer, host.household().getId());
    var middle = issuedId(issue(bearer, host.household().getId()));
    var newest = issuedId(issue(bearer, host.household().getId()));

    var firstPage = page(bearer, firstCodeOf(host.household().getId()));
    var secondPage = page(bearer, codeAfter(cursorOf(firstPage), host.household().getId()));

    assertThat(idsOf(firstPage)).containsExactly(newest);
    assertThat(firstPage.path("pageInfo").path("hasNextPage").asBoolean()).isTrue();
    assertThat(idsOf(secondPage)).containsExactly(middle);
    assertThat(secondPage.path("pageInfo").path("hasNextPage").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("Should reject a share code cursor when it continues another Household's list")
  void shouldRejectShareCodeCursorWhenItContinuesAnotherHouseholdsList() throws Exception {
    var stranger = identity();
    issue(authTestSupport.accountBearer(host), host.household().getId());
    issue(authTestSupport.accountBearer(stranger), stranger.household().getId());
    var strangersCursor =
        cursorOf(
            page(
                authTestSupport.accountBearer(stranger),
                firstCodeOf(stranger.household().getId())));

    var continued =
        executeAllowingErrors(
            authTestSupport.accountBearer(host),
            PAGE,
            codeAfter(strangersCursor, host.household().getId()));

    assertThat(errors(continued).path("extensions").path("code").asString())
        .isEqualTo("INVALID_CURSOR");
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
    assertThat(list(authTestSupport.accountBearer(host)))
        .extracting(node -> node.path("id").asString())
        .containsExactly(kept);
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
    assertThat(list(authTestSupport.accountBearer(host)))
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
  @DisplayName("Should keep the code out of every log line when a throttled Account presents it")
  void shouldKeepCodeOutOfEveryLogLineWhenThrottledAccountPresentsIt() throws Exception {
    var code = issuedCode(issue(authTestSupport.accountBearer(host), host.household().getId()));
    var presenter = identity();
    var bearer = authTestSupport.accountBearer(presenter);

    try (var logs = LogCapture.everything()) {
      lookUp(bearer, code.toLowerCase(Locale.ROOT));
      for (var failure = 0; failure < 5; failure++) {
        lookUp(bearer, "BCDF-GHJK");
      }

      var throttled = errors(executeAllowingErrors(bearer, LOOK_UP, Map.of("code", code)));
      executeAllowingErrors(
          bearer,
          OFFER,
          Map.of("profileId", presenter.profile().getId().toString(), "shareCode", code));

      assertThat(throttled.path("extensions").path("code").asString())
          .isEqualTo("TOO_MANY_CREDENTIAL_ATTEMPTS");
      assertThat(logs.renderedEvents())
          .isNotEmpty()
          .allSatisfy(
              event ->
                  assertThat(event.toUpperCase(Locale.ROOT))
                      .doesNotContain(code)
                      .doesNotContain(code.replace("-", "")));
    }
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

  @Test
  @DisplayName("Should keep the code out of the answer when a lookup names a field the input lacks")
  void shouldKeepCodeOutOfAnswerWhenLookupNamesFieldInputLacks() throws Exception {
    var code = issuedCode(issue(authTestSupport.accountBearer(host), host.household().getId()));

    var response =
        executeAllowingErrors(
            authTestSupport.accountBearer(identity()),
            """
            mutation { lookUpShareCode(input: { code: "%s", household: "Home" }) { householdName } }
            """
                .formatted(code),
            Map.of());

    assertThat(errors(response).path("message").asString()).contains("household");
    assertThat(response.toString()).doesNotContain(code);
  }

  @Test
  @DisplayName("Should keep the code out of the answer when an offer leaves out the Profile")
  void shouldKeepCodeOutOfAnswerWhenOfferLeavesOutProfile() throws Exception {
    var code = issuedCode(issue(authTestSupport.accountBearer(host), host.household().getId()));

    var response =
        executeAllowingErrors(
            authTestSupport.accountBearer(identity()),
            """
            mutation { offerProfileShare(input: { shareCode: "%s" }) { share { id } } }
            """
                .formatted(code),
            Map.of());

    assertThat(errors(response).path("message").asString()).contains("profileId");
    assertThat(response.toString()).doesNotContain(code);
  }

  @Test
  @DisplayName("Should keep the code out of the answer when a share status variable holds the code")
  void shouldKeepCodeOutOfAnswerWhenShareStatusVariableHoldsCode() throws Exception {
    var code = issuedCode(issue(authTestSupport.accountBearer(host), host.household().getId()));

    var response =
        executeAllowingErrors(
            authTestSupport.accountBearer(host),
            """
            query ($householdId: ID!, $statuses: [ProfileShareStatus!]) {
              householdShares(householdId: $householdId, statuses: $statuses) { edges { cursor } }
            }
            """,
            Map.of("householdId", host.household().getId().toString(), "statuses", List.of(code)));

    var error = errors(response);
    assertThat(error.path("message").asString()).contains("statuses");
    assertThat(error.path("extensions").path("classification").asString())
        .isEqualTo("ValidationError");
    assertThat(response.toString()).doesNotContain(code);
  }

  @Test
  @DisplayName("Should keep the code out of the answer when a lookup leaves the code unterminated")
  void shouldKeepCodeOutOfAnswerWhenLookupLeavesCodeUnterminated() throws Exception {
    var code = issuedCode(issue(authTestSupport.accountBearer(host), host.household().getId()));

    var response =
        executeAllowingErrors(
            authTestSupport.accountBearer(identity()),
            """
            mutation { lookUpShareCode(input: { code: "%s }) { householdName } }
            """
                .formatted(code),
            Map.of());

    assertSyntaxErrorAtItsLineAndColumnWithout(response, code);
  }

  @ParameterizedTest
  @MethodSource("invalidUnicodeEscapes")
  @DisplayName("Should keep the code out of the answer when a lookup escapes it as invalid unicode")
  void shouldKeepCodeOutOfAnswerWhenLookupEscapesItAsInvalidUnicode(String escape, String value)
      throws Exception {
    var response =
        executeAllowingErrors(
            authTestSupport.accountBearer(identity()),
            """
            mutation { lookUpShareCode(input: { code: "%s" }) { householdName } }
            """
                .formatted(escape),
            Map.of());

    assertSyntaxErrorAtItsLineAndColumnWithout(response, value);
  }

  static Stream<Arguments> invalidUnicodeEscapes() {
    return Stream.of(
        Arguments.of("\\u{48261379}", "48261379"), Arguments.of("\\uD800\\u4826", "4826"));
  }

  @ParameterizedTest
  @MethodSource("literalsTheParserFailsOn")
  @DisplayName("Should answer with a syntax error without the literal when the parser fails on it")
  void shouldAnswerWithSyntaxErrorWithoutLiteralWhenParserFailsOnIt(String document, String literal)
      throws Exception {
    var response =
        executeAllowingErrors(authTestSupport.accountBearer(identity()), document, Map.of());

    assertThat(errors(response).path("extensions").path("classification").asString())
        .isEqualTo("InvalidSyntax");
    assertThat(response.toString()).doesNotContain(literal);
  }

  static Stream<Arguments> literalsTheParserFailsOn() {
    return Stream.of(
        Arguments.of(lookUpOf("\\uD800\\u{123456789}"), "123456789"),
        Arguments.of(lookUpOf("\\u{BCDFDCBF}"), "BCDFDCBF"),
        Arguments.of(
            """
            mutation ($limit: Float = 1e99999999999) {
              lookUpShareCode(input: { code: "BCDF-GHJK" }) { householdName }
            }
            """,
            "99999999999"));
  }

  private static String lookUpOf(String code) {
    return """
        mutation { lookUpShareCode(input: { code: "%s" }) { householdName } }
        """
        .formatted(code);
  }

  private static void assertSyntaxErrorAtItsLineAndColumnWithout(JsonNode response, String value) {
    var error = errors(response);
    var location = error.path("locations").path(0);
    assertThat(error.path("message").asString())
        .contains(
            "line %d column %d"
                .formatted(location.path("line").asInt(), location.path("column").asInt()));
    assertThat(response.toString()).doesNotContain(value);
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

  private List<JsonNode> list(String bearer) throws Exception {
    var nodes = new ArrayList<JsonNode>();
    execute(bearer, LIST, Map.of("householdId", host.household().getId().toString()))
        .path("data")
        .path("householdShareCodes")
        .path("edges")
        .forEach(edge -> nodes.add(edge.path("node")));
    return nodes;
  }

  private JsonNode readCode(String bearer, String shareCodeId) throws Exception {
    return execute(bearer, READ, Map.of("shareCodeId", shareCodeId)).path("data").path("shareCode");
  }

  private JsonNode page(String bearer, Map<String, Object> variables) throws Exception {
    return execute(bearer, PAGE, variables).path("data").path("householdShareCodes");
  }

  private static Map<String, Object> firstCodeOf(UUID householdId) {
    return Map.of("householdId", householdId.toString(), "first", 1);
  }

  private static Map<String, Object> codeAfter(String cursor, UUID householdId) {
    return Map.of("householdId", householdId.toString(), "first", 1, "after", cursor);
  }

  private static String cursorOf(JsonNode page) {
    return page.path("edges").path(0).path("cursor").asString();
  }

  private static List<String> idsOf(JsonNode page) {
    var ids = new ArrayList<String>();
    page.path("edges").forEach(edge -> ids.add(edge.path("node").path("id").asString()));
    return ids;
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

  private int liveCodesOf(UUID householdId) {
    return dsl.fetchCount(
        SHARE_CODE,
        SHARE_CODE
            .HOUSEHOLD_ID
            .eq(householdId)
            .and(SHARE_CODE.STATUS.eq(ShareCodeStatus.LIVE))
            .and(SHARE_CODE.EXPIRES_AT.gt(OffsetDateTime.now(ZoneOffset.UTC))));
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
