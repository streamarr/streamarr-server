package com.streamarr.server.services.authorization.cedar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Intent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The Java action enum, the Cedar schema, the intent planner, and the contributors must describe
 * the same world; a drift in any one of them is a request that fails validation in production.
 */
@Tag("UnitTest")
@DisplayName("Authorization Parity Tests")
class AuthorizationParityTest {

  private static final CedarPolicyBundle BUNDLE =
      new CedarPolicyBundle(
          new BasicAuthorizationEngine(), new PathMatchingResourcePatternResolver());

  @Test
  @DisplayName(
      "Should declare every Java action with its resource type when schema parity is checked")
  void shouldDeclareEveryJavaActionWhenSchemaParityIsChecked() throws Exception {
    var actions = schemaActions();

    for (var action : Action.values()) {
      var declared = actions.get(action.cedarName());
      assertThat(declared).as("schema action for %s", action).isNotNull();
      assertThat(declared.path("appliesTo").path("resourceTypes"))
          .as("resource types of %s", action)
          .extracting(JsonNode::asText)
          .containsExactly(schemaTypeOf(action.resourceKind()));
      assertThat(declared.path("appliesTo").path("principalTypes"))
          .extracting(JsonNode::asText)
          .containsExactly("Account");
    }
  }

  private static String schemaTypeOf(Action.ResourceKind kind) {
    return switch (kind) {
      case SERVER -> "Server";
      case HOUSEHOLD -> "Household";
      case ACCOUNT -> "Account";
      case PROFILE -> "Profile";
    };
  }

  @Test
  @DisplayName(
      "Should plan every concrete schema action from at least one intent when intent parity is checked")
  void shouldPlanEveryConcreteSchemaActionWhenIntentParityIsChecked() throws Exception {
    var concrete = new ArrayList<String>();
    schemaActions()
        .properties()
        .forEach(
            entry -> {
              // Action groups carry an empty appliesTo; only leaf actions name a resource.
              if (!entry.getValue().path("appliesTo").path("resourceTypes").isEmpty()) {
                concrete.add(entry.getKey());
              }
            });
    var identity = AuthenticatedIdentityFixture.profileScopedBuilder().build();
    var planned = new LinkedHashSet<String>();
    allIntents().stream()
        .map(intent -> IntentPlanner.plan(identity, intent).check().action().cedarName())
        .forEach(planned::add);
    planned.addAll(policyChangeActions());

    assertThat(planned).containsExactlyInAnyOrderElementsOf(concrete);
    assertThat(Action.values())
        .extracting(Action::cedarName)
        .containsExactlyInAnyOrderElementsOf(concrete);
  }

  @Test
  @DisplayName("Should refuse to plan a policy change when its transition is absent")
  void shouldRefuseToPlanPolicyChangeWhenTransitionIsAbsent() {
    var identity = AuthenticatedIdentityFixture.profileScopedBuilder().build();
    var change = new Intent.ChangeProfileKind(UUID.randomUUID(), ProfileKind.ADULT);

    assertThatThrownBy(() -> IntentPlanner.plan(identity, change))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * Every transition classification, planned through the real classifier over one fake so the
   * classification-to-action map stays covered here.
   */
  private static List<String> policyChangeActions() {
    var profiles = new FakeProfileRepository();
    var planner = new ProfilePolicyPlanner(profiles);
    var kid = profiles.save(ProfileFixture.kidProfileBuilder().build());
    var ceilingedKid =
        profiles.save(ProfileFixture.kidProfileBuilder().maximumAllowedRatingAge(12).build());
    var unrestrictedAdult = profiles.save(ProfileFixture.defaultProfileBuilder().build());
    var sovereign = profiles.save(ProfileFixture.defaultProfileBuilder().build());
    profiles.linkTo(sovereign.getId(), UUID.randomUUID());

    return List.of(
        // KID gaining a ceiling: still restricted, same kind — an ordinary edit.
        actionName(
            planner.plan(new Intent.SetProfileContentCeiling(kid.getId(), 12)),
            Action.EDIT_PROFILE),
        // Ceilinged KID losing only the ceiling: still a KID — an ordinary edit.
        actionName(
            planner.plan(new Intent.ClearProfileContentCeiling(ceilingedKid.getId())),
            Action.EDIT_PROFILE),
        // An UNLINKED unrestricted Adult gaining a ceiling: its managers may restrict it.
        actionName(
            planner.plan(new Intent.SetProfileContentCeiling(unrestrictedAdult.getId(), 12)),
            Action.EDIT_PROFILE),
        // Clearing a ceiling that is not set: unrestricted stays unrestricted.
        actionName(
            planner.plan(new Intent.ClearProfileContentCeiling(unrestrictedAdult.getId())),
            Action.EDIT_PROFILE),
        // Ceilinged KID becoming a ceilinged ADULT: a kind change, still restricted.
        actionName(
            planner.plan(new Intent.ChangeProfileKind(ceilingedKid.getId(), ProfileKind.ADULT)),
            Action.CHANGE_PROFILE_KIND),
        // KID becoming an unrestricted ADULT: the final restriction lifts.
        actionName(
            planner.plan(new Intent.ChangeProfileKind(kid.getId(), ProfileKind.ADULT)),
            Action.LIFT_FINAL_RESTRICTION),
        // A linked unrestricted Adult gaining a ceiling: restricting a sovereign Adult.
        actionName(
            planner.plan(new Intent.SetProfileContentCeiling(sovereign.getId(), 12)),
            Action.RESTRICT_SOVEREIGN_ADULT));
  }

  private static String actionName(IntentPlan<?> plan, Action expected) {
    assertThat(plan.check().action()).isEqualTo(expected);
    return plan.check().action().cedarName();
  }

  @Test
  @DisplayName(
      "Should keep every Server-resource action in its administration group when action parity is checked")
  void shouldKeepEveryServerResourceActionGroupedWhenActionParityIsChecked() throws Exception {
    var actions = schemaActions();

    for (var action : Action.values()) {
      if (action.resourceKind() != Action.ResourceKind.SERVER) {
        continue;
      }
      var group = administrationGroup(action);
      assertThat(actions.get(action.cedarName()).path("memberOf"))
          .as("group of %s", action)
          .extracting(member -> member.path("id").asText())
          .containsExactly(group);
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {"householdAdministration", "invitationAdministration", "libraryAdministration"})
  @DisplayName(
      "Should keep every administration group under server administration when schema parity is checked")
  void shouldKeepEveryAdministrationGroupUnderServerAdministrationWhenSchemaParityIsChecked(
      String group) throws Exception {
    assertThat(schemaActions().get(group).path("memberOf"))
        .as("parent of %s", group)
        .extracting(member -> member.path("id").asText())
        .containsExactly("serverAdministration");
  }

  private static String administrationGroup(Action action) {
    return switch (action) {
      case CREATE_HOUSEHOLD, VIEW_HOUSEHOLDS -> "householdAdministration";
      case ISSUE_ACCOUNT_INVITATION, CANCEL_ACCOUNT_INVITATION, VIEW_ACCOUNT_INVITATIONS ->
          "invitationAdministration";
      case ADD_LIBRARY, REMOVE_LIBRARY, SCAN_LIBRARY, REFRESH_LIBRARY -> "libraryAdministration";
      case VIEW_PROFILE_PICKER,
          SELECT_PROFILE,
          PLAYBACK,
          VIEW_PROFILE_ACTIVITY,
          VIEW_HOUSEHOLD_ADMINISTRATION,
          VIEW_ACCOUNT_ADMINISTRATION,
          VIEW_PROFILE_ADMINISTRATION,
          GRANT_SERVER_ADMIN,
          REVOKE_SERVER_ADMIN,
          RENAME_HOUSEHOLD,
          RENAME_ACCOUNT,
          GRANT_HOUSEHOLD_ADMIN,
          REVOKE_HOUSEHOLD_ADMIN,
          DISABLE_ACCOUNT,
          ENABLE_ACCOUNT,
          CREATE_PROFILE,
          EDIT_PROFILE,
          CHANGE_PROFILE_KIND,
          LIFT_FINAL_RESTRICTION,
          RESTRICT_SOVEREIGN_ADULT,
          MANAGE_PROFILE_PIN,
          OVERRIDE_PROFILE_PIN,
          DELETE_PROFILE,
          ISSUE_PASSWORD_RESET ->
          throw new AssertionError("not a Server-resource action: " + action);
    };
  }

  @Test
  @DisplayName("Should require every declared fact when action coverage is checked")
  void shouldRequireEveryDeclaredFactWhenActionCoverageIsChecked() {
    var required = new ArrayList<FactRequirement>();
    for (var action : Action.values()) {
      required.addAll(action.facts());
    }

    assertThat(required).containsOnlyElementsOf(List.of(FactRequirement.values()));
    assertThat(FactRequirement.values()).allMatch(required::contains);
  }

  private static JsonNode schemaActions() throws Exception {
    return BUNDLE.schema().toJsonFormat().path("Streamarr").path("actions");
  }

  private static List<Intent<AuthorizationUnit>> allIntents() {
    var libraryId = UUID.randomUUID();
    var id = UUID.randomUUID();
    return List.of(
        new Intent.AddLibrary(),
        new Intent.RemoveLibrary(libraryId),
        new Intent.ScanLibrary(libraryId),
        new Intent.RefreshLibrary(libraryId),
        new Intent.ViewProfilePicker(),
        new Intent.SelectProfile(id, false),
        new Intent.Playback(),
        new Intent.ViewProfileActivity(id),
        new Intent.ViewHouseholdAdministration(id),
        new Intent.ViewAccountAdministration(id),
        new Intent.ViewProfileAdministration(id),
        new Intent.GrantServerAdmin(id),
        new Intent.RevokeServerAdmin(id),
        new Intent.CreateHousehold(),
        new Intent.RenameHousehold(id),
        new Intent.RenameAccount(id),
        new Intent.GrantHouseholdAdmin(id),
        new Intent.RevokeHouseholdAdmin(id),
        new Intent.DisableAccount(id),
        new Intent.EnableAccount(id),
        new Intent.ViewHouseholds(),
        new Intent.CreateProfile(id),
        new Intent.CreateProfileWithLocalManager(id),
        new Intent.RenameProfile(id),
        new Intent.SetProfilePicture(id),
        new Intent.ManageProfilePin(id),
        new Intent.OverrideProfilePin(id),
        new Intent.DeleteProfile(id),
        new Intent.IssueAccountInvitation(),
        new Intent.CancelAccountInvitation(),
        new Intent.ViewAccountInvitations(),
        new Intent.IssuePasswordReset(id));
  }
}
