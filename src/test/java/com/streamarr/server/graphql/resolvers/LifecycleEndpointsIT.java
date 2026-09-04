package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Transfers and deletion through the GraphQL boundary against real PostgreSQL and Cedar: the
 * Account moves with its Personal Profile, commit-time constraints become typed rejections, a
 * partial transfer write never disturbs credentials, and deletion paths leave nothing behind.
 */
@Tag("IntegrationTest")
@DisplayName("Lifecycle Endpoints Integration Tests")
class LifecycleEndpointsIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity admin;
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUp() {
    admin = authTestSupport.createAdminIdentity();
    host = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    authTestSupport.deleteIdentity(host);
    authTestSupport.deleteIdentity(admin);
  }

  @Test
  @DisplayName("Should preserve credentials when an Account moves with its Profile")
  void shouldPreserveCredentialsWhenAccountMovesWithProfile() throws Exception {
    var mover =
        residentOf(
            ResidentSpec.builder()
                .householdId(admin.household().getId())
                .displayName("Mover")
                .role(HouseholdRole.MEMBER)
                .build());

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { transferAccount(input: {accountId: "%s",
              destinationHouseholdId: "%s", sourceAccess: END, reason: "support"}) {
              account { householdId householdRole } userErrors { __typename } } }
            """
                .formatted(mover.getId(), host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.transferAccount.userErrors").isEmpty())
        .andExpect(
            jsonPath("$.data.transferAccount.account.householdId")
                .value(host.household().getId().toString()))
        .andExpect(jsonPath("$.data.transferAccount.account.householdRole").value("MEMBER"));

    var moved = userAccountRepository.findById(mover.getId()).orElseThrow();
    assertThat(moved.getHouseholdId()).isEqualTo(host.household().getId());
    assertThat(
            profileRepository.findById(moved.getPersonalProfileId()).orElseThrow().getHouseholdId())
        .isEqualTo(host.household().getId());
    // The partial transfer write left the password hash and display name alone.
    assertThat(moved.getPasswordHash()).isEqualTo(mover.getPasswordHash());
    assertThat(moved.getDisplayName()).isEqualTo("Mover");
    var membershipShare =
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                moved.getPersonalProfileId(), host.household().getId(), ProfileShareStatus.ACTIVE)
            .orElseThrow();
    assertThat(membershipShare.isStructural()).isTrue();
    assertThat(
            shareRepository.findByProfileIdAndHouseholdIdAndStatus(
                moved.getPersonalProfileId(), admin.household().getId(), ProfileShareStatus.ACTIVE))
        .isEmpty();
  }

  @Test
  @DisplayName("Should end the old Household visit when source access is omitted")
  void shouldEndOldHouseholdVisitWhenSourceAccessIsOmitted() throws Exception {
    var mover =
        residentOf(
            ResidentSpec.builder()
                .householdId(admin.household().getId())
                .displayName("Mover")
                .role(HouseholdRole.MEMBER)
                .build());

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { transferAccount(input: {accountId: "%s",
              destinationHouseholdId: "%s"}) {
              account { id } userErrors { __typename } } }
            """
                .formatted(mover.getId(), host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.transferAccount.userErrors").isEmpty());

    assertThat(
            shareRepository.findByProfileIdAndHouseholdIdAndStatus(
                mover.getPersonalProfileId(), admin.household().getId(), ProfileShareStatus.ACTIVE))
        .isEmpty();
  }

  @Test
  @DisplayName("Should return LastHouseholdAdmin when transfer would remove the final admin")
  void shouldReturnLastHouseholdAdminWhenTransferWouldRemoveFinalAdmin() throws Exception {
    residentOf(
        ResidentSpec.builder()
            .householdId(admin.household().getId())
            .displayName("Stays")
            .role(HouseholdRole.MEMBER)
            .build());
    graphql(
            authTestSupport.accountBearer(admin),
            transferMutation(admin.account().getId(), host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.transferAccount.userErrors[0].__typename")
                .value("LastHouseholdAdminError"));
  }

  @Test
  @DisplayName("Should return ProfileNameTaken when an Account transfer causes a name collision")
  void shouldReturnProfileNameTakenWhenAccountTransferCausesNameCollision() throws Exception {
    var mover =
        residentOf(
            ResidentSpec.builder()
                .householdId(admin.household().getId())
                .displayName("Mover")
                .role(HouseholdRole.MEMBER)
                .build());
    var twinName = profileRepository.findById(mover.getPersonalProfileId()).orElseThrow().getName();
    transactionTemplate.executeWithoutResult(
        _ -> {
          var twin =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(host.household().getId())
                      .name(twinName)
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(host.account().getId())
                  .profileId(twin.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(twin.getId())
                  .householdId(host.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
        });
    graphql(
            authTestSupport.accountBearer(admin),
            transferMutation(mover.getId(), host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.transferAccount.userErrors[0].__typename")
                .value("ProfileNameTakenError"));
  }

  @Test
  @DisplayName("Should return LastServerAdmin when deletion would remove the final ServerAdmin")
  void shouldReturnLastServerAdminWhenDeletionWouldRemoveFinalServerAdmin() throws Exception {
    residentOf(
        ResidentSpec.builder()
            .householdId(admin.household().getId())
            .displayName("Stays")
            .role(HouseholdRole.ADMIN)
            .build());
    dsl.insertInto(SERVER_BOOTSTRAP)
        .set(SERVER_BOOTSTRAP.ADMIN_ACCOUNT_ID, admin.account().getId())
        .execute();

    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { deleteAccount(input: {accountId: "%s", profileDisposition: ERASE,
              reason: "retired"}) { accountId userErrors { __typename } } }
            """
                .formatted(admin.account().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.deleteAccount.userErrors[0].__typename")
                .value("LastServerAdminError"));
  }

  @Test
  @DisplayName("Should keep the Profile with a replacement manager when an Account is deleted")
  void shouldKeepProfileWithReplacementManagerWhenAccountIsDeleted() throws Exception {
    var doomed =
        residentOf(
            ResidentSpec.builder()
                .householdId(admin.household().getId())
                .displayName("Doomed")
                .role(HouseholdRole.MEMBER)
                .build());

    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { deleteAccount(input: {accountId: "%s", profileDisposition: KEEP,
              reason: "leaving"}) { accountId userErrors { __typename } } }
            """
                .formatted(doomed.getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.deleteAccount.userErrors[0].__typename")
                .value("ReplacementManagerRequiredError"));

    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { deleteAccount(input: {accountId: "%s", profileDisposition: KEEP,
              replacementManagerAccountId: "%s", reason: "leaving"}) {
              accountId userErrors { __typename } } }
            """
                .formatted(doomed.getId(), admin.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.deleteAccount.userErrors").isEmpty());

    assertThat(userAccountRepository.findById(doomed.getId())).isEmpty();
    var preserved = profileRepository.findById(doomed.getPersonalProfileId()).orElseThrow();
    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                admin.account().getId(), preserved.getId()))
        .isTrue();
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isEqualTo(1);
  }

  @Test
  @DisplayName("Should delete their own Account when a fresh person types DELETE")
  void shouldDeleteOwnAccountWhenFreshPersonTypesDelete() throws Exception {
    var buddyId =
        residentOf(
                ResidentSpec.builder()
                    .householdId(host.household().getId())
                    .displayName("Buddy")
                    .role(HouseholdRole.ADMIN)
                    .build())
            .getId();
    assertThat(buddyId).isNotNull();

    graphql(
            authTestSupport.freshAccountBearer(host),
            """
            mutation { deleteMyAccount(input: {confirmation: "delete"}) {
              accountId userErrors { __typename } } }
            """)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.deleteMyAccount.userErrors[0].__typename")
                .value("ConfirmationRequiredError"));

    graphql(
            authTestSupport.accountBearer(host),
            """
            mutation { deleteMyAccount(input: {confirmation: "DELETE"}) {
              accountId userErrors { __typename } } }
            """)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.deleteMyAccount.userErrors[0].__typename")
                .value("ReauthenticationRequiredError"));

    graphql(
            authTestSupport.freshAccountBearer(host),
            """
            mutation { deleteMyAccount(input: {confirmation: "DELETE"}) {
              accountId userErrors { __typename } } }
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.deleteMyAccount.userErrors").isEmpty());

    assertThat(userAccountRepository.findById(host.account().getId())).isEmpty();
  }

  @Test
  @DisplayName("Should move an unlinked Profile when the destination manager is eligible")
  void shouldMoveUnlinkedProfileWhenDestinationManagerIsEligible() throws Exception {
    var orphan = managedOrphan();

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { transferProfile(input: {profileId: "%s",
              destinationHouseholdId: "%s", profileManagerAccountId: "%s"}) {
              profile { householdId } userErrors { __typename } } }
            """
                .formatted(orphan.getId(), host.household().getId(), host.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.transferProfile.userErrors").isEmpty())
        .andExpect(
            jsonPath("$.data.transferProfile.profile.householdId")
                .value(host.household().getId().toString()));

    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                host.account().getId(), orphan.getId()))
        .isTrue();
    assertThat(
            shareRepository.findByProfileIdAndHouseholdIdAndStatus(
                orphan.getId(), admin.household().getId(), ProfileShareStatus.ACTIVE))
        .isEmpty();
  }

  @Test
  @DisplayName("Should return eligible manager required when the Profile manager is omitted")
  void shouldReturnEligibleManagerRequiredWhenProfileManagerIsOmitted() throws Exception {
    var orphan = managedOrphan();

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { transferProfile(input: {profileId: "%s",
              destinationHouseholdId: "%s"}) {
              profile { id } userErrors { __typename } } }
            """
                .formatted(orphan.getId(), host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.transferProfile.userErrors[0].__typename")
                .value("EligibleProfileManagerRequiredError"));
  }

  @Test
  @DisplayName("Should return ProfileNameTaken when a Profile transfer causes a name collision")
  void shouldReturnProfileNameTakenWhenProfileTransferCausesNameCollision() throws Exception {
    var orphan = managedOrphan();
    transactionTemplate.executeWithoutResult(
        _ -> {
          var twin =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(host.household().getId())
                      .name(orphan.getName())
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(host.account().getId())
                  .profileId(twin.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(twin.getId())
                  .householdId(host.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
        });

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { transferProfile(input: {profileId: "%s",
              destinationHouseholdId: "%s", profileManagerAccountId: "%s"}) {
              profile { id } userErrors { __typename } } }
            """
                .formatted(orphan.getId(), host.household().getId(), host.account().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.transferProfile.userErrors[0].__typename")
                .value("ProfileNameTakenError"));
  }

  @Test
  @DisplayName("Should delete an unlinked Profile when a ServerAdmin is freshly reauthenticated")
  void shouldDeleteUnlinkedProfileWhenServerAdminIsFreshlyReauthenticated() throws Exception {
    var orphan = managedOrphan();
    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { administrativelyDeleteProfile(input: {profileId: "%s", reason: "abuse report"}) {
              profileId userErrors { __typename } } }
            """
                .formatted(orphan.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.administrativelyDeleteProfile.profileId")
                .value(orphan.getId().toString()))
        .andExpect(jsonPath("$.data.administrativelyDeleteProfile.userErrors").isEmpty());
    assertThat(profileRepository.findById(orphan.getId())).isEmpty();
  }

  private String transferMutation(UUID accountId, UUID destinationHouseholdId) {
    return """
           mutation { transferAccount(input: {accountId: "%s",
             destinationHouseholdId: "%s", sourceAccess: END}) {
             account { id } userErrors { __typename } } }
           """
        .formatted(accountId, destinationHouseholdId);
  }

  /** A complete Household resident: Account, Personal Profile, and membership-required share. */
  private UserAccount residentOf(ResidentSpec resident) {
    return transactionTemplate.execute(
        _ -> {
          var personal =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(resident.householdId())
                      .name(resident.displayName())
                      .build());
          var account =
              userAccountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(resident.householdId())
                      .householdRole(resident.role())
                      .displayName(resident.displayName())
                      .personalProfileId(personal.getId())
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(personal.getId())
                  .householdId(resident.householdId())
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }

  @Builder
  private record ResidentSpec(UUID householdId, String displayName, HouseholdRole role) {}

  private Profile managedOrphan() {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(admin.household().getId())
                      .name("Orphan")
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(admin.account().getId())
                  .profileId(profile.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(admin.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return profile;
        });
  }

  private ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }
}
