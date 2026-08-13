package com.streamarr.server.graphql.inputs;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileClassification;
import com.streamarr.server.services.auth.ProfileManagerOverrideAction;

public final class PortableProfileInputs {

  private PortableProfileInputs() {}

  public record ProfileCreation(
      String name,
      ProfileClassification classification,
      Integer maximumAllowedRatingAge,
      String pin) {
    @Override
    public String toString() {
      return "ProfileCreation[name=%s, classification=%s, maximumAllowedRatingAge=%s, pin=<redacted>]"
          .formatted(name, classification, maximumAllowedRatingAge);
    }
  }

  public record ProfileRename(String profileId, String name) {}

  public record ShareOffer(String profileId, String targetHouseholdId) {}

  public record ShareAcceptance(String shareId, String managementInvitationId) {}

  public record ManagerInvite(String profileId, String invitedAccountId) {}

  public record InvitationAcceptance(String invitationId) {}

  public record ProfileReference(String profileId) {}

  public record PolicyChange(
      String profileId,
      ProfileClassification classification,
      Integer maximumAllowedRatingAge,
      String pin) {
    @Override
    public String toString() {
      return "PolicyChange[profileId=%s, classification=%s, maximumAllowedRatingAge=%s, pin=<redacted>]"
          .formatted(profileId, classification, maximumAllowedRatingAge);
    }
  }

  public record ProfileDeletion(String profileId, String password) {
    @Override
    public String toString() {
      return "ProfileDeletion[profileId=%s, password=<redacted>]".formatted(profileId);
    }
  }

  public record ForceProfileDeletion(String profileId, String password, String reason) {
    @Override
    public String toString() {
      return "ForceProfileDeletion[profileId=%s, password=<redacted>, reason=%s]"
          .formatted(profileId, reason);
    }
  }

  public record ForceProfileUnshare(String shareId, String password, String reason) {
    @Override
    public String toString() {
      return "ForceProfileUnshare[shareId=%s, password=<redacted>, reason=%s]"
          .formatted(shareId, reason);
    }
  }

  public record ManagerOverride(
      String profileId,
      String targetAccountId,
      ProfileManagerOverrideAction action,
      String password,
      String reason) {
    @Override
    public String toString() {
      return "ManagerOverride[profileId=%s, targetAccountId=%s, action=%s, password=<redacted>, reason=%s]"
          .formatted(profileId, targetAccountId, action, reason);
    }
  }

  public record AccountTransfer(
      String targetAccountId,
      String targetHouseholdId,
      HouseholdRole targetRole,
      String password,
      String reason) {
    @Override
    public String toString() {
      return "AccountTransfer[targetAccountId=%s, targetHouseholdId=%s, targetRole=%s, password=<redacted>, reason=%s]"
          .formatted(targetAccountId, targetHouseholdId, targetRole, reason);
    }
  }

  public record OwnershipTransfer(
      String householdId, String targetAccountId, String password, String reason) {
    @Override
    public String toString() {
      return "OwnershipTransfer[householdId=%s, targetAccountId=%s, password=<redacted>, reason=%s]"
          .formatted(householdId, targetAccountId, reason);
    }
  }
}
