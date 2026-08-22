package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.Household.HOUSEHOLD;
import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
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
  @Autowired private AccountInvitationRepository accountInvitationRepository;
  @Autowired private ProfileManagerInvitationRepository profileManagerInvitationRepository;
  @Autowired private DeviceRegistrationRepository registrationRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MediaFileRepository mediaFileRepository;
  @Autowired private SessionProgressRepository sessionProgressRepository;
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
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    profileManagerInvitationRepository.deleteAll();
    accountInvitationRepository.deleteAll();
    registrationRepository.deleteAll();
    if (householdRepository.findById(doomed.household().getId()).isPresent()) {
      authTestSupport.deleteIdentity(doomed);
    }
    authTestSupport.deleteIdentity(admin);
    mediaFileRepository.deleteAll();
    libraryRepository.deleteAll();
  }

  @Test
  @DisplayName("Should report teardown preflight when the caller may view the Household")
  void shouldReportTeardownPreflightWhenCallerMayViewHousehold() throws Exception {
    seedTeardownArtifacts();

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
  }

  @Test
  @DisplayName("Should require reauthentication when teardown uses a stale ceremony")
  void shouldRequireReauthenticationWhenTeardownUsesStaleCeremony() throws Exception {
    graphql(
            authTestSupport.accountBearer(admin),
            tearDownMutation(doomed.household().getId(), admin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.tearDownHousehold.userErrors[0].__typename")
                .value("ReauthenticationRequiredError"));
  }

  @Test
  @DisplayName("Should return the nested destination input path when transfer omits a destination")
  void shouldReturnNestedDestinationInputPathWhenTransferOmitsDestination() throws Exception {
    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { tearDownHousehold(input: {householdId: "%s", reason: "closing",
              finalAccount: {choice: TRANSFER}}) {
              userErrors { __typename ... on InputMutationError { inputPath } } } }
            """
                .formatted(doomed.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.tearDownHousehold.userErrors[0].__typename")
                .value("DestinationRequiredError"))
        .andExpect(
            jsonPath("$.data.tearDownHousehold.userErrors[0].inputPath")
                .value(contains("finalAccount", "destinationHouseholdId")));
  }

  @Test
  @DisplayName(
      "Should return the nested replacement-manager input path when keep disposition omits a manager")
  void shouldReturnNestedReplacementManagerInputPathWhenKeepDispositionOmitsManager()
      throws Exception {
    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { tearDownHousehold(input: {householdId: "%s", reason: "closing",
              finalAccount: {choice: DELETE_KEEPING_PROFILE, destinationHouseholdId: "%s"}}) {
              userErrors { __typename ... on InputMutationError { inputPath } } } }
            """
                .formatted(doomed.household().getId(), admin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.tearDownHousehold.userErrors[0].__typename")
                .value("ReplacementManagerRequiredError"))
        .andExpect(
            jsonPath("$.data.tearDownHousehold.userErrors[0].inputPath")
                .value(contains("finalAccount", "replacementManagerAccountId")));
  }

  @Test
  @DisplayName("Should roll back teardown when it would delete the last enabled ServerAdmin")
  void shouldRollBackTeardownWhenItWouldDeleteLastEnabledServerAdmin() throws Exception {
    dsl.insertInto(SERVER_BOOTSTRAP)
        .set(SERVER_BOOTSTRAP.ADMIN_ACCOUNT_ID, admin.account().getId())
        .execute();
    var bearer = authTestSupport.freshAccountBearer(admin);

    graphql(
            bearer,
            """
            mutation { tearDownHousehold(input: {householdId: "%s", reason: "closing",
              finalAccount: {choice: DELETE}}) {
              householdId userErrors { __typename } } }
            """
                .formatted(admin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.tearDownHousehold.userErrors[0].__typename")
                .value("LastServerAdminError"));

    assertThat(householdRepository.findById(admin.household().getId())).isPresent();
    assertThat(userAccountRepository.findById(admin.account().getId())).isPresent();
    assertThat(profileRepository.findById(admin.profile().getId())).isPresent();
    assertThat(authSessionRepository.findById(admin.session().getId()).orElseThrow().getRevokedAt())
        .isNull();
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isZero();
  }

  @Test
  @DisplayName("Should delete Household artifacts when a transfer teardown succeeds")
  void shouldDeleteHouseholdArtifactsWhenTransferTeardownSucceeds() throws Exception {
    var artifacts = seedTeardownArtifacts();

    performSuccessfulTransferTeardown();

    assertThat(householdRepository.findById(doomed.household().getId())).isEmpty();
    assertThat(profileRepository.findById(artifacts.orphanProfileId())).isEmpty();
    assertThat(shareRepository.findById(artifacts.hostedVisitId())).isEmpty();
    assertThat(
            registrationRepository
                .findById(artifacts.householdRegistrationId())
                .orElseThrow()
                .getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    var moved = userAccountRepository.findById(doomed.account().getId()).orElseThrow();
    assertThat(moved.getHouseholdId()).isEqualTo(admin.household().getId());
  }

  @Test
  @DisplayName(
      "Should revoke remote registration sessions when teardown deletes their authorizing Account")
  void shouldRevokeRemoteRegistrationSessionsWhenTeardownDeletesAuthorizingAccount()
      throws Exception {
    var artifacts =
        transactionTemplate.execute(
            _ -> {
              shareRepository.saveAndFlush(
                  ProfileHouseholdShare.builder()
                      .profileId(doomed.profile().getId())
                      .householdId(admin.household().getId())
                      .status(ProfileShareStatus.ACTIVE)
                      .build());
              var registration =
                  registrationRepository.saveAndFlush(
                      DeviceRegistration.builder()
                          .esn("remote-final-account")
                          .displayName("Remote TV")
                          .householdId(admin.household().getId())
                          .authorizingAccountId(doomed.account().getId())
                          .build());
              var session =
                  authSessionRepository.saveAndFlush(
                      AuthSession.builder()
                          .accountId(admin.account().getId())
                          .deviceName("Remote TV")
                          .registrationId(registration.getId())
                          .contextHouseholdId(admin.household().getId())
                          .build());
              return new RemoteRegistrationArtifacts(registration.getId(), session.getId());
            });

    graphql(authTestSupport.freshAccountBearer(admin), deleteMutation(doomed.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.tearDownHousehold.userErrors").isEmpty());

    assertThat(
            registrationRepository.findById(artifacts.registrationId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(
            authSessionRepository.findById(artifacts.sessionId()).orElseThrow().getRevokedReason())
        .isEqualTo(SessionRevocationReason.ADMIN_REVOCATION);
  }

  @Test
  @DisplayName(
      "Should reset visitor context and revoke visited-Household devices when teardown succeeds")
  void shouldResetVisitorContextAndRevokeVisitedHouseholdDevicesWhenTeardownSucceeds()
      throws Exception {
    shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(admin.profile().getId())
            .householdId(doomed.household().getId())
            .status(ProfileShareStatus.ACTIVE)
            .build());
    var browserSession =
        authSessionRepository.saveAndFlush(
            AuthSession.builder()
                .accountId(admin.account().getId())
                .deviceName("Visitor browser")
                .contextHouseholdId(doomed.household().getId())
                .selectedProfileId(admin.profile().getId())
                .build());
    var registration =
        registrationRepository.saveAndFlush(
            DeviceRegistration.builder()
                .esn("visited-household")
                .displayName("Visited TV")
                .householdId(doomed.household().getId())
                .authorizingAccountId(admin.account().getId())
                .build());
    var deviceSession =
        authSessionRepository.saveAndFlush(
            AuthSession.builder()
                .accountId(admin.account().getId())
                .deviceName("Visited TV")
                .registrationId(registration.getId())
                .contextHouseholdId(doomed.household().getId())
                .build());

    performSuccessfulTransferTeardown();

    var reset = authSessionRepository.findById(browserSession.getId()).orElseThrow();
    assertThat(reset.getContextHouseholdId()).isNull();
    assertThat(reset.getSelectedProfileId()).isNull();
    assertThat(registrationRepository.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(
            authSessionRepository.findById(deviceSession.getId()).orElseThrow().getRevokedReason())
        .isEqualTo(SessionRevocationReason.ADMIN_REVOCATION);
  }

  @Test
  @DisplayName("Should invalidate pending Profile artifacts when teardown deletes the Profile")
  void shouldInvalidatePendingProfileArtifactsWhenTeardownDeletesProfile() throws Exception {
    var artifacts =
        transactionTemplate.execute(
            _ -> {
              var orphan =
                  profileRepository.saveAndFlush(
                      ProfileFixture.defaultProfileBuilder()
                          .householdId(doomed.household().getId())
                          .name("Pending Profile")
                          .build());
              profileManagerRepository.saveAndFlush(
                  ProfileManager.builder()
                      .accountId(doomed.account().getId())
                      .profileId(orphan.getId())
                      .build());
              var accountInvitation =
                  accountInvitationRepository.saveAndFlush(
                      AccountInvitation.builder()
                          .recipientEmail("pending@example.com")
                          .householdId(admin.household().getId())
                          .householdName("Destination")
                          .householdRole(HouseholdRole.MEMBER)
                          .profileId(orphan.getId())
                          .profileName(orphan.getName())
                          .profileKind(ProfileKind.ADULT)
                          .issuerAccountId(admin.account().getId())
                          .expiresAt(Instant.now().plusSeconds(3600))
                          .publicId("pending-profile-account")
                          .secretDigest(new byte[] {1})
                          .build());
              var managerInvitation =
                  profileManagerInvitationRepository.saveAndFlush(
                      ProfileManagerInvitation.builder()
                          .profileId(orphan.getId())
                          .profileName(orphan.getName())
                          .inviterAccountId(doomed.account().getId())
                          .inviterDisplayName(doomed.account().getDisplayName())
                          .recipientAccountId(admin.account().getId())
                          .recipientEmail(admin.account().getEmail())
                          .expiresAt(Instant.now().plusSeconds(3600))
                          .publicId("pending-profile-manager")
                          .secretDigest(new byte[] {2})
                          .build());
              return new PendingProfileArtifacts(
                  accountInvitation.getId(), managerInvitation.getId());
            });

    performSuccessfulTransferTeardown();

    assertThat(
            accountInvitationRepository
                .findById(artifacts.accountInvitationId())
                .orElseThrow()
                .getStatus())
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(
            profileManagerInvitationRepository
                .findById(artifacts.managerInvitationId())
                .orElseThrow()
                .getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should return the teardown audit row when ServerAdmin reads the security audit")
  void shouldReturnTeardownAuditRowWhenServerAdminReadsSecurityAudit() throws Exception {
    var beforeTeardown = Instant.now();
    performSuccessfulTransferTeardown();
    var afterTeardown = Instant.now();

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
            .andExpect(
                jsonPath("$.data.securityAuditEvents.edges[0].node.actorAccountId")
                    .value(admin.account().getId().toString()))
            .andExpect(
                jsonPath("$.data.securityAuditEvents.edges[0].node.reason").value("closing shop"))
            .andExpect(
                jsonPath("$.data.securityAuditEvents.edges[0].node.outcome").value("SUCCESS"))
            .andExpect(
                jsonPath("$.data.securityAuditEvents.edges[0].node.resources")
                    .value(containsString(doomed.household().getId().toString())))
            .andExpect(jsonPath("$.data.securityAuditEvents.edges[0].node.occurredAt").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var auditNode = objectMapper.readTree(firstPage).at("/data/securityAuditEvents/edges/0/node");
    assertThat(Instant.parse(auditNode.path("occurredAt").asString()))
        .isBetween(beforeTeardown, afterTeardown);
    assertThat(
            dsl.fetchCount(
                SECURITY_AUDIT_EVENT, SECURITY_AUDIT_EVENT.OPERATION.eq("tearDownHousehold")))
        .isEqualTo(1);
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
  }

  @Test
  @DisplayName("Should forbid the security audit when the caller is not ServerAdmin")
  void shouldForbidSecurityAuditWhenCallerIsNotServerAdmin() throws Exception {
    graphql(
            authTestSupport.accountBearer(doomed),
            "query { securityAuditEvents(first: 10) { edges { node { operation } } } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("Should return empty Profile activity when the Profile is hidden")
  void shouldReturnEmptyProfileActivityWhenProfileIsHidden() throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    saveProgress(library, admin.profile().getId(), "hidden.mkv");

    graphql(
            authTestSupport.profileBearer(doomed),
            """
            query { profileActivity(profileId: "%s") { edges { node { id } } } }
            """
                .formatted(admin.account().getPersonalProfileId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.profileActivity.edges").isEmpty());
  }

  @Test
  @DisplayName("Should return newest Profile activity first when paging through visible activity")
  void shouldReturnNewestProfileActivityFirstWhenPagingThroughVisibleActivity() throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var older = saveProgress(library, doomed.profile().getId(), "older.mkv");
    var newer = saveProgress(library, doomed.profile().getId(), "newer.mkv");
    setProgressTime(older.getId(), Instant.parse("2026-08-01T00:00:00Z"));
    setProgressTime(newer.getId(), Instant.parse("2026-08-02T00:00:00Z"));

    var firstPage =
        graphql(
                authTestSupport.profileBearer(doomed),
                """
                query { profileActivity(profileId: "%s", first: 1) {
                  edges { cursor node { id } } pageInfo { hasNextPage } } }
                """
                    .formatted(doomed.profile().getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(
                jsonPath("$.data.profileActivity.edges[0].node.id").value(newer.getId().toString()))
            .andExpect(jsonPath("$.data.profileActivity.pageInfo.hasNextPage").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var cursor =
        objectMapper.readTree(firstPage).at("/data/profileActivity/edges/0/cursor").asString();

    graphql(
            authTestSupport.profileBearer(doomed),
            """
            query { profileActivity(profileId: "%s", first: 1, after: "%s") {
              edges { node { id } } } }
            """
                .formatted(doomed.profile().getId(), cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.profileActivity.edges[0].node.id").value(older.getId().toString()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-an-instant|00000000-0000-0000-0000-000000000001",
        "2026-08-01T00:00:00Z|not-a-uuid"
      })
  @DisplayName("Should return an invalid cursor when an audit cursor contains a malformed field")
  void shouldReturnInvalidCursorWhenAuditCursorContainsMalformedField(String key) throws Exception {
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
  @DisplayName(
      "Should return newest-first rows before a cursor when paging backward through the audit")
  void shouldReturnNewestFirstRowsBeforeCursorWhenPagingBackwardThroughAudit() throws Exception {
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
  @DisplayName("Should use the audit identifier as a tiebreaker when timestamps are equal")
  void shouldUseAuditIdentifierAsTiebreakerWhenTimestampsAreEqual() throws Exception {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    var occurredAt = Instant.parse("2026-08-01T00:00:00Z");
    var ids =
        List.of(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            UUID.fromString("00000000-0000-0000-0000-000000000004"));
    for (var index = 0; index < ids.size(); index++) {
      dsl.insertInto(SECURITY_AUDIT_EVENT)
          .set(SECURITY_AUDIT_EVENT.ID, ids.get(index))
          .set(SECURITY_AUDIT_EVENT.OCCURRED_AT, occurredAt.atOffset(ZoneOffset.UTC))
          .set(SECURITY_AUDIT_EVENT.OPERATION, Character.toString('A' + index))
          .set(SECURITY_AUDIT_EVENT.OUTCOME, "SUCCESS")
          .execute();
    }
    var afterKey = occurredAt + "|" + ids.get(2);
    var after =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(afterKey.getBytes(StandardCharsets.UTF_8));

    graphql(
            authTestSupport.accountBearer(admin),
            """
            query { securityAuditEvents(first: 2, after: "%s") {
              edges { node { operation } } } }
            """
                .formatted(after))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.securityAuditEvents.edges[*].node.operation")
                .value(contains("B", "A")));
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
  @DisplayName(
      "Should accept one disposition and reject the other when final-Account requests race")
  void shouldAcceptOneDispositionAndRejectOtherWhenFinalAccountRequestsRace() throws Exception {
    var bearer = authTestSupport.freshAccountBearer(admin);
    var householdId = doomed.household().getId();
    var lockHeld = new CountDownLatch(1);
    var releaseLock = new CountDownLatch(1);
    var transfer = tearDownMutation(householdId, admin.household().getId());
    var delete =
        """
        mutation { tearDownHousehold(input: {householdId: "%s", reason: "closing shop",
          finalAccount: {choice: DELETE}}) {
          householdId userErrors { __typename } } }
        """
            .formatted(householdId);

    List<String> responses;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        dsl.select(HOUSEHOLD.ID)
                            .from(HOUSEHOLD)
                            .where(HOUSEHOLD.ID.eq(householdId))
                            .forUpdate()
                            .fetch();
                        lockHeld.countDown();
                        awaitLatch(releaseLock, "release the held Household lock");
                      }));
      assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();
      var transferRequest =
          executor.submit(
              () -> graphql(bearer, transfer).andReturn().getResponse().getContentAsString());
      var deleteRequest =
          executor.submit(
              () -> graphql(bearer, delete).andReturn().getResponse().getContentAsString());
      try {
        await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(waitingLocks()).isEqualTo(2));
      } finally {
        releaseLock.countDown();
      }
      blocker.get(5, TimeUnit.SECONDS);
      responses =
          List.of(transferRequest.get(5, TimeUnit.SECONDS), deleteRequest.get(5, TimeUnit.SECONDS));
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
    assertThat(
            dsl.fetchCount(
                SECURITY_AUDIT_EVENT, SECURITY_AUDIT_EVENT.OPERATION.eq("tearDownHousehold")))
        .isEqualTo(1);
  }

  private TeardownArtifacts seedTeardownArtifacts() {
    return transactionTemplate.execute(
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
          return new TeardownArtifacts(orphan.getId(), visit.getId(), registration.getId());
        });
  }

  private SessionProgress saveProgress(Library library, UUID profileId, String filename) {
    var mediaFile =
        mediaFileRepository.saveAndFlush(
            MediaFile.builder()
                .libraryId(library.getId())
                .status(MediaFileStatus.MATCHED)
                .filename(filename)
                .filepathUri("file:///media/" + UUID.randomUUID() + "/" + filename)
                .build());
    return sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .sessionId(UUID.randomUUID())
            .profileId(profileId)
            .mediaFileId(mediaFile.getId())
            .positionSeconds(60)
            .percentComplete(10.0)
            .durationSeconds(600)
            .build());
  }

  private void setProgressTime(UUID progressId, Instant time) {
    dsl.update(SESSION_PROGRESS)
        .set(SESSION_PROGRESS.LAST_MODIFIED_ON, time.atOffset(ZoneOffset.UTC))
        .where(SESSION_PROGRESS.ID.eq(progressId))
        .execute();
  }

  private void performSuccessfulTransferTeardown() throws Exception {
    graphql(
            authTestSupport.freshAccountBearer(admin),
            tearDownMutation(doomed.household().getId(), admin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.tearDownHousehold.userErrors").isEmpty());
  }

  private String tearDownMutation(UUID householdId, UUID destination) {
    return """
           mutation { tearDownHousehold(input: {householdId: "%s", reason: "closing shop",
             finalAccount: {choice: TRANSFER, destinationHouseholdId: "%s"}}) {
             householdId userErrors { __typename } } }
           """
        .formatted(householdId, destination);
  }

  private String deleteMutation(UUID householdId) {
    return """
           mutation { tearDownHousehold(input: {householdId: "%s", reason: "closing shop",
             finalAccount: {choice: DELETE}}) {
             householdId userErrors { __typename } } }
           """
        .formatted(householdId);
  }

  private ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }

  private int waitingLocks() {
    var activity = DSL.table(DSL.name("pg_catalog", "pg_stat_activity"));
    var waitEventType = DSL.field(DSL.name("wait_event_type"), String.class);
    var query = DSL.field(DSL.name("query"), String.class);
    return dsl.fetchCount(
        activity,
        waitEventType
            .eq("Lock")
            .and(query.likeIgnoreCase("%household%"))
            .and(query.likeIgnoreCase("%for update%")));
  }

  private void awaitLatch(CountDownLatch latch, String action) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting to " + action);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting to " + action, exception);
    }
  }

  private record TeardownArtifacts(
      UUID orphanProfileId, UUID hostedVisitId, UUID householdRegistrationId) {}

  private record RemoteRegistrationArtifacts(UUID registrationId, UUID sessionId) {}

  private record PendingProfileArtifacts(UUID accountInvitationId, UUID managerInvitationId) {}
}
