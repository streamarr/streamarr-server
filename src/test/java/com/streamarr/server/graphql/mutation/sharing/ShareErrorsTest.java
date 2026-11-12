package com.streamarr.server.graphql.mutation.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.streamarr.server.services.identity.ProfileRejections;
import com.streamarr.server.services.identity.ShareCodeRejections;
import com.streamarr.server.services.identity.ShareRejections;
import com.streamarr.server.services.serversettings.ChangeLifetimeRejection;
import com.streamarr.server.services.serversettings.ChangeShareCodeLimitRejection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Share Errors Tests")
class ShareErrorsTest {

  @Test
  @DisplayName("Should map every offer rejection when an offer fails")
  void shouldMapEveryOfferRejectionWhenOfferFails() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(ShareErrors.toOfferError(new ShareRejections.ProfileNotFound()))
              .isExactlyInstanceOf(ProfileNotFoundError.class);
          softly
              .assertThat(ShareErrors.toOfferError(new ShareCodeRejections.ShareCodeRefused()))
              .isExactlyInstanceOf(ShareCodeRefusedError.class);
          softly
              .assertThat(ShareErrors.toOfferError(new ShareRejections.AlreadyShared()))
              .isExactlyInstanceOf(ProfileAlreadySharedError.class);
          softly
              .assertThat(
                  ShareErrors.toOfferError(
                      new ProfileRejections.ManagersOnly(
                          ProfileRejections.ManagersOnlyReason.NOT_MANAGER)))
              .isEqualTo(
                  new ProfileManagersOnlyError(
                      "Only the Profile's managers may do this; supervising it does not cover"
                          + " this.",
                      ProfileRejections.ManagersOnlyReason.NOT_MANAGER));
        });
  }

  @Test
  @DisplayName(
      "Should refuse as a lookup does, on the share code input, when an offer's code fails")
  void shouldRefuseAsLookupDoesOnShareCodeInputWhenOffersCodeFails() {
    assertThat(ShareErrors.toOfferError(new ShareCodeRejections.ShareCodeRefused()))
        .isEqualTo(
            new ShareCodeRefusedError(
                "This share code does not work. Ask the Household for a new one.",
                List.of("shareCode")));
  }

  @Test
  @DisplayName("Should point at the Profile when it is already available where the code leads")
  void shouldPointAtProfileWhenItIsAlreadyAvailableWhereCodeLeads() {
    assertThat(ShareErrors.toOfferError(new ShareRejections.AlreadyShared()))
        .isEqualTo(
            new ProfileAlreadySharedError(
                "That Profile is already available in that Household.", List.of("profileId")));
  }

  @Test
  @DisplayName("Should name the hours input and the allowed range when a lifetime is out of range")
  void shouldNameHoursInputAndAllowedRangeWhenLifetimeIsOutOfRange() {
    assertThat(ShareErrors.toLifetimeError(new ChangeLifetimeRejection.OutOfRange()))
        .isEqualTo(new LifetimeOutOfRangeError("Choose from 1 to 720 hours.", List.of("hours")));
  }

  @Test
  @DisplayName("Should name the codes input and the allowed range when a limit is out of range")
  void shouldNameCodesInputAndAllowedRangeWhenLimitIsOutOfRange() {
    assertThat(ShareErrors.toShareCodeLimitError(new ChangeShareCodeLimitRejection.OutOfRange()))
        .isEqualTo(
            new ShareCodeLimitOutOfRangeError("Choose from 1 to 100 codes.", List.of("codes")));
  }

  @Test
  @DisplayName("Should ask to withdraw a code when the Household holds the limit of live codes")
  void shouldAskToWithdrawACodeWhenTheHouseholdHoldsTheLimitOfLiveCodes() {
    assertThat(ShareErrors.toIssueShareCodeError(new ShareCodeRejections.ShareCodeLimitReached()))
        .isEqualTo(new ShareCodeLimitReachedError("Withdraw a code before issuing another."));
  }

  @Test
  @DisplayName("Should name the Household input when issuing a share code fails")
  void shouldNameHouseholdInputWhenIssuingShareCodeFails() {
    assertThat(ShareErrors.toIssueShareCodeError(new ShareCodeRejections.HouseholdNotFound()))
        .isEqualTo(new HouseholdNotFoundError("No such Household.", List.of("householdId")));
  }

  @Test
  @DisplayName("Should name the share code input when withdrawing a share code fails")
  void shouldNameShareCodeInputWhenWithdrawingShareCodeFails() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(
                  ShareErrors.toWithdrawShareCodeError(new ShareCodeRejections.ShareCodeNotFound()))
              .isEqualTo(new ShareCodeNotFoundError("No such share code.", List.of("shareCodeId")));
          softly
              .assertThat(
                  ShareErrors.toWithdrawShareCodeError(new ShareCodeRejections.ShareCodeNotLive()))
              .isInstanceOf(ShareCodeNotLiveError.class)
              .extracting(error -> ((ShareCodeNotLiveError) error).inputPath())
              .isEqualTo(List.of("shareCodeId"));
        });
  }

  @Test
  @DisplayName("Should refuse in plain words naming no Household when a looked-up code fails")
  void shouldRefuseInPlainWordsNamingNoHouseholdWhenLookedUpCodeFails() {
    assertThat(ShareErrors.toLookUpShareCodeError(new ShareCodeRejections.ShareCodeRefused()))
        .isEqualTo(
            new ShareCodeRefusedError(
                "This share code does not work. Ask the Household for a new one.",
                List.of("code")));
  }

  @Test
  @DisplayName("Should map every acceptance rejection when acceptance fails")
  void shouldMapEveryAcceptanceRejectionWhenAcceptanceFails() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(ShareErrors.toAcceptError(new ShareRejections.ShareNotFound()))
              .isExactlyInstanceOf(ShareNotFoundError.class);
          softly
              .assertThat(ShareErrors.toAcceptError(new ShareRejections.ShareNotPending()))
              .isExactlyInstanceOf(ShareNotPendingError.class);
          softly
              .assertThat(ShareErrors.toAcceptError(new ShareRejections.OfferInvalidated()))
              .isExactlyInstanceOf(OfferInvalidatedError.class);
          softly
              .assertThat(ShareErrors.toAcceptError(new ShareRejections.NoEligibleAdmin()))
              .isExactlyInstanceOf(RestrictedProfileRequiresHouseholdAdminError.class);
          softly
              .assertThat(ShareErrors.toAcceptError(new ShareRejections.NameConflict()))
              .isExactlyInstanceOf(ShareNameConflictError.class);
        });
  }

  @Test
  @DisplayName(
      "Should name the refused action and tell the person to reload when Accept, Decline or Cancel"
          + " finds the share invitation not pending")
  void shouldNameTheRefusedActionWhenShareInvitationIsNotPending() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(
                  ShareErrors.toAcceptError(new ShareRejections.ShareNotPending()).message())
              .isEqualTo(
                  "This share invitation can't be accepted anymore. Reload the page to see what"
                      + " changed.");
          softly
              .assertThat(
                  ShareErrors.toRejectError(new ShareRejections.ShareNotPending()).message())
              .isEqualTo(
                  "This share invitation can't be declined anymore. Reload the page to see what"
                      + " changed.");
          softly
              .assertThat(
                  ShareErrors.toCancelError(new ShareRejections.ShareNotPending()).message())
              .isEqualTo(
                  "This share invitation can't be canceled anymore. Reload the page to see what"
                      + " changed.");
        });
  }

  @Test
  @DisplayName(
      "Should say the share invitation was withdrawn without the stored reason when Accept finds"
          + " it withdrawn")
  void shouldSayShareInvitationWasWithdrawnWithoutStoredReasonWhenAcceptFindsItWithdrawn() {
    var error = ShareErrors.toAcceptError(new ShareRejections.OfferInvalidated());

    assertThat(error.message())
        .isEqualTo(
            "This share invitation was withdrawn and can't be accepted. Reload the page to see if"
                + " other share invitations are available.");
  }

  @Test
  @DisplayName("Should map every decision rejection when rejecting an offer fails")
  void shouldMapEveryDecisionRejectionWhenRejectingOfferFails() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(ShareErrors.toRejectError(new ShareRejections.ShareNotFound()))
              .isExactlyInstanceOf(ShareNotFoundError.class);
          softly
              .assertThat(ShareErrors.toRejectError(new ShareRejections.ShareNotPending()))
              .isExactlyInstanceOf(ShareNotPendingError.class);
        });
  }

  @Test
  @DisplayName("Should map every cancellation rejection when cancellation fails")
  void shouldMapEveryCancellationRejectionWhenCancellationFails() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(ShareErrors.toCancelError(new ShareRejections.ShareNotFound()))
              .isExactlyInstanceOf(ShareNotFoundError.class);
          softly
              .assertThat(ShareErrors.toCancelError(new ShareRejections.ShareNotPending()))
              .isExactlyInstanceOf(ShareNotPendingError.class);
        });
  }

  @Test
  @DisplayName("Should map every end rejection when an ordinary end fails")
  void shouldMapEveryEndRejectionWhenOrdinaryEndFails() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(ShareErrors.toEndError(new ShareRejections.ShareNotFound()))
              .isExactlyInstanceOf(ShareNotFoundError.class);
          softly
              .assertThat(ShareErrors.toEndError(new ShareRejections.ShareNotActive()))
              .isExactlyInstanceOf(ShareNotActiveError.class);
          softly
              .assertThat(ShareErrors.toEndError(new ShareRejections.StructuralShareCannotEnd()))
              .isExactlyInstanceOf(MembershipShareCannotEndError.class);
        });
  }

  @Test
  @DisplayName("Should map every administrative-end rejection when an administrative end fails")
  void shouldMapEveryAdministrativelyEndRejectionWhenAdministrativelyEndFails() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(
                  ShareErrors.toAdministrativelyEndError(new ShareRejections.ShareNotFound()))
              .isExactlyInstanceOf(ShareNotFoundError.class);
          softly
              .assertThat(
                  ShareErrors.toAdministrativelyEndError(new ShareRejections.ShareNotActive()))
              .isExactlyInstanceOf(ShareNotActiveError.class);
          softly
              .assertThat(
                  ShareErrors.toAdministrativelyEndError(
                      new ShareRejections.StructuralShareCannotEnd()))
              .isExactlyInstanceOf(MembershipShareCannotEndError.class);
          softly
              .assertThat(
                  ShareErrors.toAdministrativelyEndError(new ShareRejections.ReasonRequired()))
              .isExactlyInstanceOf(ReasonRequiredError.class);
          softly
              .assertThat(
                  ShareErrors.toAdministrativelyEndError(
                      new ShareRejections.ReauthenticationRequired()))
              .isExactlyInstanceOf(ReauthenticationRequiredError.class);
        });
  }
}
