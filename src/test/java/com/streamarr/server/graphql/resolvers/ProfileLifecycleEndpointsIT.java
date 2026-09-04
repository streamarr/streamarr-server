package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Profile Lifecycle Endpoints Integration Tests")
class ProfileLifecycleEndpointsIT extends IdentityLifecycleEndpointTestSupport {

  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private TransactionTemplate transactionTemplate;

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

  @ParameterizedTest(name = "Should return an input error when {0} is malformed")
  @MethodSource("malformedProfileLifecycleIds")
  @DisplayName("Should return an input error when a Profile lifecycle mutation ID is malformed")
  void shouldReturnInputErrorWhenProfileLifecycleMutationIdIsMalformed(
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

  static Stream<MalformedLifecycleIdCase> malformedProfileLifecycleIds() {
    return Stream.of(
        MalformedLifecycleIdCase.builder()
            .operation("transferProfile")
            .resource("profile")
            .inputPath("profileId")
            .mutationTemplate(
                lifecycleMutation(
                    "transferProfile(input: {profileId: \"not-a-uuid\", destinationHouseholdId: \"%2$s\", profileManagerAccountId: \"%1$s\"})",
                    "profile"))
            .build(),
        MalformedLifecycleIdCase.builder()
            .operation("transferProfile")
            .resource("profile")
            .inputPath("destinationHouseholdId")
            .mutationTemplate(
                lifecycleMutation(
                    "transferProfile(input: {profileId: \"%3$s\", destinationHouseholdId: \"not-a-uuid\", profileManagerAccountId: \"%1$s\"})",
                    "profile"))
            .build(),
        MalformedLifecycleIdCase.builder()
            .operation("transferProfile")
            .resource("profile")
            .inputPath("profileManagerAccountId")
            .mutationTemplate(
                lifecycleMutation(
                    "transferProfile(input: {profileId: \"%3$s\", destinationHouseholdId: \"%2$s\", profileManagerAccountId: \"not-a-uuid\"})",
                    "profile"))
            .build(),
        MalformedLifecycleIdCase.builder()
            .operation("administrativelyDeleteProfile")
            .resource("profileId")
            .inputPath("profileId")
            .mutationTemplate(
                lifecycleMutation(
                    "administrativelyDeleteProfile(input: {profileId: \"not-a-uuid\", reason: \"reason\"})",
                    "profileId"))
            .build());
  }

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
}
