package com.streamarr.server.graphql.mutation.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.streamarr.server.services.identity.ShareCodeRejections;
import com.streamarr.server.services.identity.ShareRejections;
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
              .assertThat(ShareErrors.toOfferError(new ShareRejections.HouseholdNotFound()))
              .isExactlyInstanceOf(HouseholdNotFoundError.class);
          softly
              .assertThat(ShareErrors.toOfferError(new ShareRejections.AlreadyShared()))
              .isExactlyInstanceOf(ProfileAlreadySharedError.class);
        });
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
