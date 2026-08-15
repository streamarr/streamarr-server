package com.streamarr.server.graphql.inputs;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.services.auth.ProfileManagerOverrideAction;

public final class PortableProfileInputs {

  private PortableProfileInputs() {}

  public record ProfileCreation(
      String name, ProfileKind kind, Integer maximumAllowedRatingAge, String pin) {
    /**
     * Formats the profile creation input for display while redacting the PIN.
     *
     * @return a textual representation containing the profile name, kind, and maximum allowed rating age
     */
    @Override
    public String toString() {
      return "ProfileCreation[name=%s, kind=%s, maximumAllowedRatingAge=%s, pin=<redacted>]"
          .formatted(name, kind, maximumAllowedRatingAge);
    }
  }

  public record ProfileRename(String profileId, String name) {}

  public record ShareOffer(String profileId, String targetHouseholdId) {}

  public record ShareAcceptance(String shareId, String managementInvitationId) {}

  public record ManagerInvite(String profileId, String invitedAccountId) {}

  public record InvitationAcceptance(String invitationId) {}

  public record ProfileReference(String profileId) {}

  public record ProfileKindChange(String profileId, ProfileKind kind) {}

  public record ProfileContentCeilingChange(String profileId, int maximumAllowedRatingAge) {}

  public record ProfilePinReset(String profileId, String newPin) {
    /**
     * Formats the profile PIN reset details while redacting the replacement PIN.
     *
     * @return a string containing the profile identifier and a redacted PIN
     */
    @Override
    public String toString() {
      return "ProfilePinReset[profileId=%s, newPin=<redacted>]".formatted(profileId);
    }
  }

  public record ProfileDeletion(String profileId, String password) {
    /**
     * Formats the profile deletion input while redacting its password.
     *
     * @return a string containing the profile ID and a redacted password
     */
    @Override
    public String toString() {
      return "ProfileDeletion[profileId=%s, password=<redacted>]".formatted(profileId);
    }
  }

  public record ForceProfileDeletion(String profileId, String password, String reason) {
    /**
     * Formats this deletion request without exposing its password.
     *
     * @return a representation containing the profile identifier and deletion reason, with the password redacted
     */
    @Override
    public String toString() {
      return "ForceProfileDeletion[profileId=%s, password=<redacted>, reason=%s]"
          .formatted(profileId, reason);
    }
  }

  public record ForceProfileUnshare(String shareId, String password, String reason) {
    /**
     * Formats this unshare request with its password redacted.
     *
     * @return a representation containing the share ID, redacted password, and reason
     */
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
    /**
     * Formats this manager override with its password redacted.
     *
     * @return a representation of this override with the password replaced by {@code <redacted>}
     */
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
    /**
     * Formats the account transfer details while redacting the password.
     *
     * @return a string containing the transfer details without the password
     */
    @Override
    public String toString() {
      return "AccountTransfer[targetAccountId=%s, targetHouseholdId=%s, targetRole=%s, password=<redacted>, reason=%s]"
          .formatted(targetAccountId, targetHouseholdId, targetRole, reason);
    }
  }

  public record OwnershipTransfer(
      String householdId, String targetAccountId, String password, String reason) {
    /**
     * Returns a representation of this transfer request with the password redacted.
     *
     * @return a string containing the household ID, target account ID, and reason
     */
    @Override
    public String toString() {
      return "OwnershipTransfer[householdId=%s, targetAccountId=%s, password=<redacted>, reason=%s]"
          .formatted(householdId, targetAccountId, reason);
    }
  }
}
