package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transfers and deletion through the GraphQL boundary against real PostgreSQL and Cedar: the
 * Account moves with its Personal Profile, commit-time constraints become typed rejections, a
 * partial transfer write never disturbs credentials, and deletion paths leave nothing behind.
 */
@Tag("IntegrationTest")
@DisplayName("Account Lifecycle Endpoints Integration Tests")
class AccountLifecycleEndpointsIT extends IdentityLifecycleEndpointTestSupport {

  @Autowired private HouseholdRepository householdRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private TransactionTemplate transactionTemplate;

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
  @DisplayName(
      "Should return restricted Profile supervision and roll back when a restricted Account enters an empty Household")
  void
      shouldReturnRestrictedProfileSupervisionAndRollBackWhenRestrictedAccountEntersEmptyHousehold()
          throws Exception {
    var mover =
        residentOf(
            ResidentSpec.builder()
                .householdId(admin.household().getId())
                .displayName("Restricted mover")
                .role(HouseholdRole.MEMBER)
                .build());
    restrictUnderSupervision(mover);
    var emptyHousehold =
        householdRepository.saveAndFlush(HouseholdFixture.defaultHouseholdBuilder().build());

    try {
      graphql(
              authTestSupport.accountBearer(admin),
              transferMutation(mover.getId(), emptyHousehold.getId()))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.data.transferAccount.userErrors[0].__typename")
                  .value("RestrictedProfileRequiresHouseholdAdminError"));

      assertThat(userAccountRepository.findById(mover.getId()).orElseThrow().getHouseholdId())
          .isEqualTo(admin.household().getId());
      assertThat(userAccountRepository.findByHouseholdId(emptyHousehold.getId())).isEmpty();
    } finally {
      householdRepository.deleteById(emptyHousehold.getId());
    }
  }

  @Test
  @DisplayName(
      "Should delete an Account when a freshly reauthenticated ServerAdmin uses the administrative override")
  void shouldDeleteAccountWhenFreshlyReauthenticatedServerAdminUsesAdministrativeOverride()
      throws Exception {
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
            mutation { administrativelyDeleteAccount(input: {accountId: "%s",
              profileDisposition: ERASE, reason: "retired"}) {
              accountId userErrors { __typename } } }
            """
                .formatted(doomed.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.administrativelyDeleteAccount.accountId")
                .value(doomed.getId().toString()))
        .andExpect(jsonPath("$.data.administrativelyDeleteAccount.userErrors").isEmpty());

    assertThat(userAccountRepository.findById(doomed.getId())).isEmpty();
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
            mutation { administrativelyDeleteAccount(input: {accountId: "%s", profileDisposition: ERASE,
              reason: "retired"}) { accountId userErrors { __typename } } }
            """
                .formatted(admin.account().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.administrativelyDeleteAccount.userErrors[0].__typename")
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
            mutation { administrativelyDeleteAccount(input: {accountId: "%s", profileDisposition: KEEP,
              reason: "leaving"}) { accountId userErrors { __typename } } }
            """
                .formatted(doomed.getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.administrativelyDeleteAccount.userErrors[0].__typename")
                .value("ReplacementManagerRequiredError"));

    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { administrativelyDeleteAccount(input: {accountId: "%s", profileDisposition: KEEP,
              replacementManagerAccountId: "%s", reason: "leaving"}) {
              accountId userErrors { __typename } } }
            """
                .formatted(doomed.getId(), admin.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.administrativelyDeleteAccount.userErrors").isEmpty());

    assertThat(userAccountRepository.findById(doomed.getId())).isEmpty();
    var preserved = profileRepository.findById(doomed.getPersonalProfileId()).orElseThrow();
    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                admin.account().getId(), preserved.getId()))
        .isTrue();
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should return eligible manager required and roll back when deletion removes a restricted Profile manager")
  void shouldReturnEligibleManagerRequiredAndRollBackWhenDeletionRemovesRestrictedProfileManager()
      throws Exception {
    var doomed =
        residentOf(
            ResidentSpec.builder()
                .householdId(admin.household().getId())
                .displayName("Doomed manager")
                .role(HouseholdRole.ADMIN)
                .build());
    var restricted = restrictedProfileManagedBy(doomed);

    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { administrativelyDeleteAccount(input: {accountId: "%s", profileDisposition: ERASE,
              reason: "leaving"}) { accountId userErrors { __typename } } }
            """
                .formatted(doomed.getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.administrativelyDeleteAccount.userErrors[0].__typename")
                .value("ProfileRequiresEligibleManagerError"));

    assertThat(userAccountRepository.findById(doomed.getId())).isPresent();
    assertThat(profileRepository.findById(doomed.getPersonalProfileId())).isPresent();
    assertThat(profileRepository.findById(restricted.getId())).isPresent();
    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                doomed.getId(), restricted.getId()))
        .isTrue();
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isZero();
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

  @ParameterizedTest(name = "Should return an input error when {0} is malformed")
  @MethodSource("malformedAccountLifecycleIds")
  @DisplayName("Should return an input error when an Account lifecycle mutation ID is malformed")
  void shouldReturnInputErrorWhenAccountLifecycleMutationIdIsMalformed(
      MalformedLifecycleIdCase testCase) throws Exception {
    var mutation =
        testCase
            .mutationTemplate()
            .formatted(admin.account().getId(), host.household().getId(), host.profile().getId());

    graphql(authTestSupport.freshAccountBearer(admin), mutation)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.%s.%s".formatted(testCase.operation(), testCase.resource()))
                .doesNotExist())
        .andExpect(
            jsonPath("$.data.%s.userErrors[0].__typename".formatted(testCase.operation()))
                .value("InvalidIdError"))
        .andExpect(
            jsonPath("$.data.%s.userErrors[0].inputPath[0]".formatted(testCase.operation()))
                .value(testCase.inputPath()));
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

  private void restrictUnderSupervision(UserAccount account) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(admin.account().getId())
                  .profileId(account.getPersonalProfileId())
                  .build());
          var profile = profileRepository.findById(account.getPersonalProfileId()).orElseThrow();
          profile.setMaximumAllowedRatingAge(12);
          profileRepository.saveAndFlush(profile);
        });
  }

  private Profile restrictedProfileManagedBy(UserAccount manager) {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.kidProfileBuilder()
                      .householdId(manager.getHouseholdId())
                      .name("Managed child")
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(manager.getId())
                  .profileId(profile.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(manager.getHouseholdId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return profile;
        });
  }

  static Stream<MalformedLifecycleIdCase> malformedAccountLifecycleIds() {
    return Stream.of(
        MalformedLifecycleIdCase.builder()
            .operation("transferAccount")
            .resource("account")
            .inputPath("accountId")
            .mutationTemplate(
                lifecycleMutation(
                    "transferAccount(input: {accountId: \"not-a-uuid\", destinationHouseholdId: \"%2$s\"})",
                    "account"))
            .build(),
        MalformedLifecycleIdCase.builder()
            .operation("transferAccount")
            .resource("account")
            .inputPath("destinationHouseholdId")
            .mutationTemplate(
                lifecycleMutation(
                    "transferAccount(input: {accountId: \"%1$s\", destinationHouseholdId: \"not-a-uuid\"})",
                    "account"))
            .build(),
        MalformedLifecycleIdCase.builder()
            .operation("administrativelyDeleteAccount")
            .resource("accountId")
            .inputPath("accountId")
            .mutationTemplate(
                lifecycleMutation(
                    "administrativelyDeleteAccount(input: {accountId: \"not-a-uuid\", profileDisposition: ERASE, reason: \"reason\"})",
                    "accountId"))
            .build(),
        MalformedLifecycleIdCase.builder()
            .operation("administrativelyDeleteAccount")
            .resource("accountId")
            .inputPath("replacementManagerAccountId")
            .mutationTemplate(
                lifecycleMutation(
                    "administrativelyDeleteAccount(input: {accountId: \"%1$s\", profileDisposition: KEEP, replacementManagerAccountId: \"not-a-uuid\", reason: \"reason\"})",
                    "accountId"))
            .build());
  }
}
