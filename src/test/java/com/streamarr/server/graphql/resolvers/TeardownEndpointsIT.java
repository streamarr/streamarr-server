package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Household teardown and the audit surfaces against real PostgreSQL and Cedar: the final Account
 * leaves by its chosen disposition, nothing outlives the Household, and only ServerAdmin reads the
 * audit trail.
 */
@Tag("IntegrationTest")
@DisplayName("Teardown Endpoints Integration Tests")
class TeardownEndpointsIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private DeviceRegistrationRepository registrationRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity admin;
  private AuthTestSupport.TestIdentity doomed;

  @BeforeEach
  void setUp() {
    admin = authTestSupport.createAdminIdentity();
    doomed = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    registrationRepository.deleteAll();
    if (householdRepository.findById(doomed.household().getId()).isPresent()) {
      authTestSupport.deleteIdentity(doomed);
    }
    authTestSupport.deleteIdentity(admin);
  }

  @Test
  @DisplayName("Should tear the Household down leaving nothing stranded")
  void shouldTearHouseholdDownLeavingNothingStranded() throws Exception {
    // A resident orphan Profile, a hosted visit, and a registered TV all fall with it.
    var state =
        transactionTemplate.execute(
            _ -> {
              var orphan =
                  profileRepository.saveAndFlush(
                      ProfileFixture.defaultProfileBuilder()
                          .householdId(doomed.household().getId())
                          .name("Orphan")
                          .build());
              profileManagerRepository.saveAndFlush(
                  ProfileManager.builder()
                      .accountId(doomed.account().getId())
                      .profileId(orphan.getId())
                      .build());
              var visit =
                  shareRepository.saveAndFlush(
                      ProfileHouseholdShare.builder()
                          .profileId(admin.account().getPersonalProfileId())
                          .householdId(doomed.household().getId())
                          .status(ProfileShareStatus.ACTIVE)
                          .build());
              var registration =
                  registrationRepository.saveAndFlush(
                      DeviceRegistration.builder()
                          .esn("esn-doomed")
                          .displayName("TV")
                          .householdId(doomed.household().getId())
                          .authorizingAccountId(admin.account().getId())
                          .build());
              return new Object[] {orphan.getId(), visit.getId(), registration.getId()};
            });

    // The preflight names what teardown will take with it.
    graphql(
            authTestSupport.accountBearer(admin),
            """
            query { teardownPreflight(householdId: "%s") {
              accountCount unlinkedProfiles { name } hostedVisitCount } }
            """
                .formatted(doomed.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.teardownPreflight.accountCount").value(1))
        .andExpect(jsonPath("$.data.teardownPreflight.unlinkedProfiles[0].name").value("Orphan"));

    // A stale ceremony earns the typed answer; the fresh one with a disposition tears down.
    graphql(
            authTestSupport.accountBearer(admin),
            tearDownMutation(doomed.household().getId(), admin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.tearDownHousehold.userErrors[0].__typename")
                .value("ReauthenticationRequiredError"));
    graphql(
            authTestSupport.freshAccountBearer(admin),
            tearDownMutation(doomed.household().getId(), admin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.tearDownHousehold.userErrors").isEmpty());

    assertThat(householdRepository.findById(doomed.household().getId())).isEmpty();
    assertThat(profileRepository.findById((UUID) state[0])).isEmpty();
    // The visit ended in the transaction, then fell with the Household row itself.
    assertThat(shareRepository.findById((UUID) state[1])).isEmpty();
    assertThat(registrationRepository.findById((UUID) state[2]).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    // The final Account arrived in the destination with its Personal Profile.
    var moved = userAccountRepository.findById(doomed.account().getId()).orElseThrow();
    assertThat(moved.getHouseholdId()).isEqualTo(admin.household().getId());

    // Only ServerAdmin reads the audit; the row is there, newest first, and the keyset cursor
    // resumes strictly after the page already seen.
    var firstPage =
        graphql(
                authTestSupport.accountBearer(admin),
                "query { securityAuditEvents(first: 1) { edges { cursor node { operation reason"
                    + " outcome occurredAt resources actorAccountId id } } pageInfo { hasNextPage"
                    + " } } }")
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.data.securityAuditEvents.edges[0].node.operation")
                    .value("tearDownHousehold"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var endCursor =
        objectMapper
            .readTree(firstPage)
            .path("data")
            .path("securityAuditEvents")
            .path("edges")
            .get(0)
            .path("cursor")
            .asString();
    graphql(
            authTestSupport.accountBearer(admin),
            """
            query { securityAuditEvents(first: 5, after: "%s") {
              edges { node { operation } } } }
            """
                .formatted(endCursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.securityAuditEvents.edges[*].node.operation")
                .value(not(hasItem("tearDownHousehold"))));
    graphql(
            authTestSupport.accountBearer(doomed),
            "query { securityAuditEvents(first: 10) { edges { node { operation } } } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

    // Activity reads scope by visibility: the hidden Profile reads as empty.
    graphql(
            authTestSupport.accountBearer(admin),
            """
            query { profileActivity(profileId: "%s") { edges { node { id } } } }
            """
                .formatted(admin.account().getPersonalProfileId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.profileActivity.edges").isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-an-instant|00000000-0000-0000-0000-000000000001",
        "2026-08-01T00:00:00Z|not-a-uuid"
      })
  @DisplayName("Should classify malformed audit cursor fields as invalid cursors")
  void shouldClassifyMalformedAuditCursorFieldsAsInvalidCursors(String key) throws Exception {
    var cursor =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(key.getBytes(StandardCharsets.UTF_8));

    graphql(
            authTestSupport.accountBearer(admin),
            "query { securityAuditEvents(first: 1, after: \"%s\") { edges { node { id } } } }"
                .formatted(cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_CURSOR"));
  }

  @Test
  @DisplayName("Should return the rows immediately before an audit cursor newest first")
  void shouldReturnRowsImmediatelyBeforeAuditCursorNewestFirst() throws Exception {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    var base = Instant.parse("2026-08-01T00:00:00Z");
    var ids =
        List.of(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            UUID.fromString("00000000-0000-0000-0000-000000000004"),
            UUID.fromString("00000000-0000-0000-0000-000000000005"));
    for (var index = 0; index < ids.size(); index++) {
      dsl.insertInto(SECURITY_AUDIT_EVENT)
          .set(SECURITY_AUDIT_EVENT.ID, ids.get(index))
          .set(SECURITY_AUDIT_EVENT.OCCURRED_AT, base.minusSeconds(index).atOffset(ZoneOffset.UTC))
          .set(SECURITY_AUDIT_EVENT.OPERATION, Character.toString('A' + index))
          .set(SECURITY_AUDIT_EVENT.OUTCOME, "SUCCESS")
          .execute();
    }
    var beforeKey = base.minusSeconds(3) + "|" + ids.get(3);
    var before =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(beforeKey.getBytes(StandardCharsets.UTF_8));

    graphql(
            authTestSupport.accountBearer(admin),
            ("query { securityAuditEvents(last: 2, before: \"%s\") {"
                    + " edges { node { operation } }"
                    + " pageInfo { hasPreviousPage hasNextPage } } }")
                .formatted(before))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.securityAuditEvents.edges[*].node.operation")
                .value(contains("B", "C")))
        .andExpect(jsonPath("$.data.securityAuditEvents.pageInfo.hasPreviousPage").value(true))
        .andExpect(jsonPath("$.data.securityAuditEvents.pageInfo.hasNextPage").value(true));
  }

  @Test
  @DisplayName("Should clear a revoked session selection when teardown deletes its Profile")
  void shouldClearRevokedSessionSelectionWhenTeardownDeletesItsProfile() throws Exception {
    var sessionId =
        transactionTemplate.execute(
            _ -> {
              var orphan =
                  profileRepository.saveAndFlush(
                      ProfileFixture.defaultProfileBuilder()
                          .householdId(doomed.household().getId())
                          .build());
              profileManagerRepository.saveAndFlush(
                  ProfileManager.builder()
                      .accountId(doomed.account().getId())
                      .profileId(orphan.getId())
                      .build());
              return authSessionRepository
                  .saveAndFlush(
                      AuthSession.builder()
                          .accountId(admin.account().getId())
                          .deviceName("revoked selection")
                          .contextHouseholdId(doomed.household().getId())
                          .selectedProfileId(orphan.getId())
                          .revokedAt(Instant.now())
                          .revokedReason(SessionRevocationReason.ADMIN_REVOCATION)
                          .build())
                  .getId();
            });

    graphql(
            authTestSupport.freshAccountBearer(admin),
            tearDownMutation(doomed.household().getId(), admin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.tearDownHousehold.userErrors").isEmpty());

    assertThat(authSessionRepository.findById(sessionId).orElseThrow().getSelectedProfileId())
        .isNull();
  }

  @Test
  @DisplayName("Should accept only one concurrent final-Account disposition")
  void shouldAcceptOnlyOneConcurrentFinalAccountDisposition() throws Exception {
    var bearer = authTestSupport.freshAccountBearer(admin);
    var householdId = doomed.household().getId();
    var start = new CyclicBarrier(2);
    var transfer = tearDownMutation(householdId, admin.household().getId());
    var delete =
        """
        mutation { tearDownHousehold(input: {householdId: "%s", reason: "closing shop",
          finalAccount: {choice: DELETE}}) {
          householdId userErrors { __typename } } }
        """
            .formatted(householdId);
    List<Callable<String>> requests =
        List.of(
            () -> concurrentGraphql(start, bearer, transfer),
            () -> concurrentGraphql(start, bearer, delete));

    List<String> responses;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      responses =
          executor.invokeAll(requests).stream()
              .map(
                  future -> {
                    try {
                      return future.get();
                    } catch (Exception exception) {
                      throw new AssertionError("concurrent teardown request failed", exception);
                    }
                  })
              .toList();
    }

    var payloads = responses.stream().map(objectMapper::readTree).toList();
    assertThat(payloads)
        .filteredOn(
            payload ->
                householdId
                    .toString()
                    .equals(payload.at("/data/tearDownHousehold/householdId").asString()))
        .hasSize(1);
    assertThat(payloads)
        .filteredOn(
            payload ->
                "HouseholdNotFoundError"
                    .equals(
                        payload.at("/data/tearDownHousehold/userErrors/0/__typename").asString()))
        .hasSize(1);
  }

  private String tearDownMutation(UUID householdId, UUID destination) {
    return """
           mutation { tearDownHousehold(input: {householdId: "%s", reason: "closing shop",
             finalAccount: {choice: TRANSFER, destinationHouseholdId: "%s"}}) {
             householdId userErrors { __typename } } }
           """
        .formatted(householdId, destination);
  }

  private ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }

  private String concurrentGraphql(CyclicBarrier start, String bearer, String query)
      throws Exception {
    start.await(5, TimeUnit.SECONDS);
    return graphql(bearer, query).andReturn().getResponse().getContentAsString();
  }
}
