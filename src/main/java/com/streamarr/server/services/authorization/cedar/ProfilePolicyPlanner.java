package com.streamarr.server.services.authorization.cedar;

import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfilePolicySnapshot;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfilePolicyTransition;
import com.streamarr.server.services.authorization.ProfilePolicyTransition.Classification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Plans a kind or ceiling change (ADR 0025): reads the Profile's current policy under the caller's
 * transaction lock, classifies the exact transition, chooses the Cedar action that classification
 * answers to, and returns the normalized target as the decision's value. A missing Profile plans
 * against the ordinary edit action with no facts, which policy denies.
 */
@Component
@RequiredArgsConstructor
class ProfilePolicyPlanner {

  private final ProfileRepository profileRepository;

  IntentPlan<ProfilePolicyTransition> plan(Intent.ProfilePolicyChange intent) {
    var profileId = intent.profileId();
    var current = profileRepository.lockPolicyById(profileId);
    if (current.isEmpty()) {
      return new IntentPlan<>(
          AuthorizationCheck.onProfile(Action.EDIT_PROFILE, profileId),
          new ProfilePolicyTransition(null, null, Classification.ORDINARY_EDIT));
    }
    var transition = classify(current.get(), intent);
    return new IntentPlan<>(
        AuthorizationCheck.onProfile(actionFor(transition.classification()), profileId),
        transition);
  }

  private static ProfilePolicyTransition classify(
      ProfilePolicySnapshot current, Intent.ProfilePolicyChange intent) {
    var targetKind =
        intent instanceof Intent.ChangeProfileKind(var ignored, var kind) ? kind : current.kind();
    var targetCeiling =
        switch (intent) {
          case Intent.ChangeProfileKind _ -> current.maximumAllowedRatingAge();
          case Intent.SetProfileContentCeiling(var ignored, var age) -> Integer.valueOf(age);
          case Intent.ClearProfileContentCeiling _ -> null;
        };
    var restrictedAfter = targetKind == ProfileKind.KID || targetCeiling != null;
    return new ProfilePolicyTransition(
        targetKind, targetCeiling, classification(current, targetKind, restrictedAfter));
  }

  private static Classification classification(
      ProfilePolicySnapshot current, ProfileKind targetKind, boolean restrictedAfter) {
    if (!current.restricted() && restrictedAfter && current.linked()) {
      return Classification.RESTRICT_SOVEREIGN_ADULT;
    }
    if (current.restricted() && !restrictedAfter) {
      return Classification.LIFT_FINAL_RESTRICTION;
    }
    if (targetKind != current.kind()) {
      return Classification.KIND_CHANGE;
    }
    return Classification.ORDINARY_EDIT;
  }

  private static Action actionFor(Classification classification) {
    return switch (classification) {
      case ORDINARY_EDIT -> Action.EDIT_PROFILE;
      case KIND_CHANGE -> Action.CHANGE_PROFILE_KIND;
      case LIFT_FINAL_RESTRICTION -> Action.LIFT_FINAL_RESTRICTION;
      case RESTRICT_SOVEREIGN_ADULT -> Action.RESTRICT_SOVEREIGN_ADULT;
    };
  }
}
