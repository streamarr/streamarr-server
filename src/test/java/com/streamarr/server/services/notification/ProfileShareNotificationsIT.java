package com.streamarr.server.services.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SourceHouseholdAccess;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareAnswer;
import com.streamarr.server.domain.notification.ProfileShareEndedBy;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.domain.notification.RequestResolution;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.auth.AccountInvitationService;
import com.streamarr.server.services.auth.AccountInvitationService.AcceptInvitationCommand;
import com.streamarr.server.services.auth.ProfileShareExpirySweeper;
import com.streamarr.server.services.identity.AccountLifecycleService;
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import com.streamarr.server.services.identity.CredentialIssuanceService;
import com.streamarr.server.services.identity.CredentialIssuanceService.IssueInvitationForProfileCommand;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.services.identity.ShareCodeCommand;
import com.streamarr.server.services.identity.ShareRejections;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The sharing kinds of ADR 0063, made in the transaction of the share write that causes them: Offer
 * waiting for the target Household's HouseholdAdmins, Offer answered for the offering side, and
 * Share ended for the side that did not end it. The Account that acts is never told.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Share Notifications Integration Tests")
class ProfileShareNotificationsIT extends AbstractIntegrationTest {

  private static final String IP_ADDRESS = "203.0.113.7";
  private static final String CONNECTED_EMAIL = "ollie@example.com";

  @Autowired private ProfileSharingService sharing;
  @Autowired private AccountLifecycleService accountLifecycle;
  @Autowired private CredentialIssuanceService credentialIssuance;
  @Autowired private AccountInvitationService accountInvitations;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private ProfileShareExpirySweeper expirySweeper;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private AuthTestSupport.TestIdentity sam;
  private AuthTestSupport.TestIdentity riverside;
  private AuthTestSupport.TestIdentity riversideAdmin;

  @BeforeEach
  void setUp() {
    sam = track(authTestSupport.createIdentity());
    riverside = track(authTestSupport.createIdentity());
    riversideAdmin = secondAdminOf(riverside);
  }

  @AfterEach
  void tearDown() {
    invitationRepository.findAll().stream()
        .filter(invitation -> CONNECTED_EMAIL.equalsIgnoreCase(invitation.getRecipientEmail()))
        .forEach(invitationRepository::delete);
    userAccountRepository
        .findByEmailIgnoreCase(CONNECTED_EMAIL)
        .ifPresent(account -> authTestSupport.deleteAccount(account.getId()));
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should give each HouseholdAdmin of the target Household a request when a Profile is offered")
  void shouldGiveEachHouseholdAdminOfTheTargetHouseholdARequestWhenAProfileIsOffered() {
    var offer = offer(sam, sam.profile(), riverside);

    assertThat(notificationsAbout(offer))
        .allSatisfy(
            notification -> {
              assertThat(notification.getKind()).isEqualTo(NotificationKind.PROFILE_SHARE_OFFERED);
              assertThat(notification.getForm()).isEqualTo(NotificationForm.REQUEST);
              assertThat(notification.getFacts())
                  .isEqualTo(ProfileShareFacts.offered(sam.profile().getName()));
            })
        .extracting(Notification::getRecipientAccountId)
        .containsExactlyInAnyOrder(riverside.account().getId(), riversideAdmin.account().getId());
  }

  @Test
  @DisplayName(
      "Should tell the Account that made the offer nothing when it administers the target"
          + " Household")
  void shouldTellTheAccountThatMadeTheOfferNothingWhenItAdministersTheTargetHousehold() {
    var kai = track(authTestSupport.createIdentity());
    var ollie = profileManagedBy("Ollie", kai, kai, riverside);

    var offer = offer(riverside, ollie, riverside);

    assertThat(notificationsAbout(offer))
        .extracting(Notification::getRecipientAccountId)
        .containsExactly(riversideAdmin.account().getId());
  }

  @Test
  @DisplayName("Should give no request to a HouseholdAdmin who was promoted after the offer")
  void shouldGiveNoRequestToAHouseholdAdminWhoWasPromotedAfterTheOffer() {
    var member = track(authTestSupport.createHouseholdMemberIdentity(riverside));
    var offer = offer(sam, sam.profile(), riverside);

    transactionTemplate.executeWithoutResult(
        _ -> userAccountRepository.tryPromoteToHouseholdAdmin(member.account().getId()));

    assertThat(notificationsOf(member)).isEmpty();
    assertThat(notificationsAbout(offer)).hasSize(2);
  }

  @Test
  @DisplayName("Should still give a request to a HouseholdAdmin whose Account is disabled")
  void shouldStillGiveARequestToAHouseholdAdminWhoseAccountIsDisabled() {
    transactionTemplate.executeWithoutResult(
        _ -> userAccountRepository.tryDisable(riversideAdmin.account().getId()));

    offer(sam, sam.profile(), riverside);

    assertThat(notificationsOf(riversideAdmin))
        .extracting(Notification::getKind)
        .containsExactly(NotificationKind.PROFILE_SHARE_OFFERED);
  }

  @Test
  @DisplayName(
      "Should give new requests and withdraw the older ones when an offer replaces an older offer")
  void shouldGiveNewRequestsAndWithdrawTheOlderOnesWhenAnOfferReplacesAnOlderOffer() {
    var older = offer(sam, sam.profile(), riverside);

    var newer = offer(sam, sam.profile(), riverside);

    assertThat(notificationsOf(riverside))
        .extracting(Notification::getShareId)
        .containsExactlyInAnyOrder(older.getId(), newer.getId());
    assertThat(resolutionOf(older)).contains(RequestResolution.WITHDRAWN);
    assertThat(resolutionOf(newer)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should tell the offering side, except the Account that answered, when an offer is accepted")
  void shouldTellTheOfferingSideExceptTheAccountThatAnsweredWhenAnOfferIsAccepted() {
    var theo = track(authTestSupport.createHouseholdMemberIdentity(sam));
    var ollie = profileManagedBy("Ollie", sam, sam, theo);
    var offer = offer(sam, ollie, riverside);

    assertThat(sharing.acceptProfileShare(authTestSupport.identityOf(riverside), offer.getId()))
        .isInstanceOf(Outcome.Accepted.class);

    assertThat(answersAbout(offer))
        .allSatisfy(
            notification ->
                assertThat(notification.getFacts())
                    .isEqualTo(ProfileShareFacts.answered("Ollie", ProfileShareAnswer.ACCEPTED)))
        .extracting(Notification::getRecipientAccountId)
        .containsExactlyInAnyOrder(sam.account().getId(), theo.account().getId());
    assertThat(resolutionOf(offer)).contains(RequestResolution.DECIDED);
  }

  @Test
  @DisplayName(
      "Should tell the Account that made the offer when a ServerAdmin offered for a Profile")
  void shouldTellTheAccountThatMadeTheOfferWhenAServerAdminOfferedForAProfile() {
    var admin = track(authTestSupport.createAdminIdentity());
    var ollie = profileManagedBy("Ollie", sam, sam);
    var offer = offer(admin, ollie, riverside);

    sharing.rejectProfileShare(authTestSupport.identityOf(riverside), offer.getId());

    assertThat(answersAbout(offer))
        .allSatisfy(
            notification ->
                assertThat(notification.getFacts())
                    .isEqualTo(ProfileShareFacts.answered("Ollie", ProfileShareAnswer.REJECTED)))
        .extracting(Notification::getRecipientAccountId)
        .containsExactlyInAnyOrder(sam.account().getId(), admin.account().getId());
  }

  @Test
  @DisplayName("Should tell nobody anything new when the offer is canceled")
  void shouldTellNobodyAnythingNewWhenTheOfferIsCanceled() {
    var offer = offer(sam, sam.profile(), riverside);

    sharing.cancelProfileShare(authTestSupport.identityOf(sam), offer.getId());

    assertThat(notificationsAbout(offer))
        .extracting(Notification::getKind)
        .containsOnly(NotificationKind.PROFILE_SHARE_OFFERED);
    assertThat(resolutionOf(offer)).contains(RequestResolution.WITHDRAWN);
  }

  @Test
  @DisplayName("Should tell nobody anything new when the expiry sweep stores an offer's expiry")
  void shouldTellNobodyAnythingNewWhenTheExpirySweepStoresAnOffersExpiry() {
    var offer = offer(sam, sam.profile(), riverside);
    lapse(offer);

    expirySweeper.sweep();

    assertThat(stored(offer).getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
    assertThat(notificationsAbout(offer))
        .extracting(Notification::getKind)
        .containsOnly(NotificationKind.PROFILE_SHARE_OFFERED);
    assertThat(resolutionOf(offer)).contains(RequestResolution.EXPIRED);
  }

  @Test
  @DisplayName("Should make no Notification when the acceptance that would make it rolls back")
  void shouldMakeNoNotificationWhenTheAcceptanceThatWouldMakeItRollsBack() {
    var ollie = profileManagedBy("Ollie", sam, sam);
    profileManagedBy("Ollie", riverside, riverside);
    var offer = offer(sam, ollie, riverside);

    var outcome = sharing.acceptProfileShare(authTestSupport.identityOf(riverside), offer.getId());

    assertThat(outcome).isEqualTo(Outcome.rejected(new ShareRejections.NameConflict()));
    assertThat(answersAbout(offer)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should tell the offering side when a HouseholdAdmin of the Household ends the share")
  void shouldTellTheOfferingSideWhenAHouseholdAdminOfTheHouseholdEndsTheShare() {
    var share = accepted(offer(sam, sam.profile(), riverside));

    sharing.endProfileShare(authTestSupport.identityOf(riversideAdmin), share.getId());

    assertThat(endingsAbout(share))
        .singleElement()
        .satisfies(
            notification -> {
              assertThat(notification.getRecipientAccountId()).isEqualTo(sam.account().getId());
              assertThat(notification.getFacts())
                  .isEqualTo(
                      ProfileShareFacts.ended(
                          sam.profile().getName(), ProfileShareEndedBy.HOUSEHOLD_SIDE));
            });
  }

  @Test
  @DisplayName("Should tell the Household's side when the Profile's own Account ends the share")
  void shouldTellTheHouseholdsSideWhenTheProfilesOwnAccountEndsTheShare() {
    var share = accepted(offer(sam, sam.profile(), riverside));

    sharing.endProfileShare(authTestSupport.identityOf(sam), share.getId());

    assertThat(endingsAbout(share))
        .allSatisfy(
            notification ->
                assertThat(notification.getFacts().endedBy())
                    .contains(ProfileShareEndedBy.OFFERING_SIDE))
        .extracting(Notification::getRecipientAccountId)
        .containsExactlyInAnyOrder(riverside.account().getId(), riversideAdmin.account().getId());
  }

  @Test
  @DisplayName(
      "Should end the share for the Household's side when its ender also manages the Profile")
  void shouldEndTheShareForTheHouseholdsSideWhenItsEnderAlsoManagesTheProfile() {
    var ollie = profileManagedBy("Ollie", sam, sam, riversideAdmin);
    var share = accepted(offer(sam, ollie, riverside));

    sharing.endProfileShare(authTestSupport.identityOf(riversideAdmin), share.getId());

    assertThat(endingsAbout(share))
        .allSatisfy(
            notification ->
                assertThat(notification.getFacts().endedBy())
                    .contains(ProfileShareEndedBy.HOUSEHOLD_SIDE))
        .extracting(Notification::getRecipientAccountId)
        .containsExactly(sam.account().getId());
  }

  @Test
  @DisplayName(
      "Should tell both sides once each, except the ServerAdmin, when a ServerAdmin ends the share")
  void shouldTellBothSidesOnceEachExceptTheServerAdminWhenAServerAdminEndsTheShare() {
    var admin = track(authTestSupport.createAdminIdentity());
    var ollie = profileManagedBy("Ollie", sam, sam, riversideAdmin, admin);
    var share = accepted(offer(sam, ollie, riverside));

    var outcome =
        sharing.administrativelyEndProfileShare(
            authTestSupport.freshIdentityOf(admin), share.getId(), "requested by Sam");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(endingsAbout(share))
        .allSatisfy(
            notification ->
                assertThat(notification.getFacts().endedBy()).contains(ProfileShareEndedBy.SERVER))
        .extracting(Notification::getRecipientAccountId)
        .containsExactlyInAnyOrder(
            sam.account().getId(), riverside.account().getId(), riversideAdmin.account().getId());
  }

  @Test
  @DisplayName("Should keep the answer and the end side by side when an accepted share later ends")
  void shouldKeepTheAnswerAndTheEndSideBySideWhenAnAcceptedShareLaterEnds() {
    var ollie = profileManagedBy("Ollie", sam, sam);
    var share = accepted(offer(sam, ollie, riverside));

    sharing.endProfileShare(authTestSupport.identityOf(riverside), share.getId());

    assertThat(notificationsOf(sam))
        .extracting(Notification::getKind)
        .containsExactlyInAnyOrder(
            NotificationKind.PROFILE_SHARE_ANSWERED, NotificationKind.PROFILE_SHARE_ENDED);
  }

  @Test
  @DisplayName(
      "Should tell both sides, except the ServerAdmin, when an Account transfer ends the share into"
          + " the Household it leaves")
  void
      shouldTellBothSidesExceptTheServerAdminWhenAnAccountTransferEndsTheShareIntoTheHouseholdItLeaves() {
    var admin = track(authTestSupport.createAdminIdentity());
    var ben = track(authTestSupport.createHouseholdMemberIdentity(riverside));
    var home = shareOf(ben.profile(), riverside);

    var outcome =
        accountLifecycle.transferAccount(
            authTestSupport.freshIdentityOf(admin),
            TransferAccountCommand.builder()
                .accountId(ben.account().getId())
                .destinationHouseholdId(sam.household().getId())
                .sourceHouseholdAccess(SourceHouseholdAccess.END)
                .reason("moved in with Sam")
                .build());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(endingsAbout(home))
        .allSatisfy(
            notification ->
                assertThat(notification.getFacts())
                    .isEqualTo(
                        ProfileShareFacts.ended(
                            ben.profile().getName(), ProfileShareEndedBy.SERVER)))
        .extracting(Notification::getRecipientAccountId)
        .containsExactlyInAnyOrder(
            ben.account().getId(), riverside.account().getId(), riversideAdmin.account().getId());
  }

  @Test
  @DisplayName(
      "Should tell nobody anything new when a rejection comes after the offer's time ran out")
  void shouldTellNobodyAnythingNewWhenARejectionComesAfterTheOffersTimeRanOut() {
    var offer = offer(sam, sam.profile(), riverside);
    lapse(offer);

    sharing.rejectProfileShare(authTestSupport.identityOf(riverside), offer.getId());

    assertThat(answersAbout(offer)).isEmpty();
    assertThat(resolutionOf(offer)).contains(RequestResolution.EXPIRED);
  }

  @Test
  @DisplayName(
      "Should tell both sides of each visit, except the new Account, when a Profile is connected to"
          + " an Account")
  void shouldTellBothSidesOfEachVisitExceptTheNewAccountWhenAProfileIsConnectedToAnAccount() {
    var kai = track(authTestSupport.createIdentity());
    var ollie = profileManagedBy("Ollie", sam, sam);
    var visit = accepted(offer(sam, ollie, riverside));
    var waiting = offer(sam, ollie, kai);

    var connected = connect(ollie, riverside);

    assertThat(endingsAbout(visit))
        .allSatisfy(
            notification ->
                assertThat(notification.getFacts())
                    .isEqualTo(ProfileShareFacts.ended("Ollie", ProfileShareEndedBy.SERVER)))
        .extracting(Notification::getRecipientAccountId)
        .containsExactlyInAnyOrder(
            sam.account().getId(), riverside.account().getId(), riversideAdmin.account().getId());
    assertThat(notificationsOf(connected)).isEmpty();
    assertThat(resolutionOf(waiting)).contains(RequestResolution.WITHDRAWN);
    assertThat(notificationsAbout(waiting))
        .extracting(Notification::getKind)
        .containsOnly(NotificationKind.PROFILE_SHARE_OFFERED);
  }

  @Test
  @DisplayName(
      "Should give each HouseholdAdmin a request when connecting a Profile offers it to them again")
  void shouldGiveEachHouseholdAdminARequestWhenConnectingAProfileOffersItToThemAgain() {
    var ollie = profileManagedBy("Ollie", sam, sam);
    accepted(offer(sam, ollie, riverside));

    connect(ollie, riverside);

    var reoffer =
        shareRepository.findByProfileId(ollie.getId()).stream()
            .filter(share -> share.getStatus() == ProfileShareStatus.PENDING)
            .filter(share -> share.getHouseholdId().equals(riverside.household().getId()))
            .findFirst()
            .orElseThrow();
    assertThat(notificationsAbout(reoffer))
        .allSatisfy(
            notification ->
                assertThat(notification.getFacts()).isEqualTo(ProfileShareFacts.offered("Ollie")))
        .extracting(Notification::getRecipientAccountId)
        .containsExactlyInAnyOrder(riverside.account().getId(), riversideAdmin.account().getId());
  }

  private ProfileHouseholdShare shareOf(Profile profile, AuthTestSupport.TestIdentity household) {
    return shareRepository
        .findByProfileIdAndHouseholdIdAndStatus(
            profile.getId(), household.household().getId(), ProfileShareStatus.ACTIVE)
        .orElseThrow();
  }

  private ProfileHouseholdShare offer(
      AuthTestSupport.TestIdentity offerer, Profile profile, AuthTestSupport.TestIdentity target) {
    var code = authTestSupport.shareCodeFor(target);
    return sharing
        .offerProfileShare(
            authTestSupport.identityOf(offerer),
            profile.getId(),
            ShareCodeCommand.builder().code(code).ipAddress(IP_ADDRESS).build())
        .fold(
            share -> share,
            rejection -> {
              throw new IllegalStateException("The offer was refused: " + rejection);
            });
  }

  private ProfileHouseholdShare accepted(ProfileHouseholdShare offer) {
    return sharing
        .acceptProfileShare(authTestSupport.identityOf(riverside), offer.getId())
        .fold(
            share -> share,
            rejection -> {
              throw new IllegalStateException("The offer was not accepted: " + rejection);
            });
  }

  /**
   * A ServerAdmin issues an Account invitation for the Profile, which offers it again to the
   * Household, and the invitation is accepted: the Profile becomes the new Account's Personal
   * Profile.
   */
  private UUID connect(Profile profile, AuthTestSupport.TestIdentity reofferedTo) {
    var admin = track(authTestSupport.createAdminIdentity());
    var issued =
        credentialIssuance
            .issueAccountInvitationForProfile(
                authTestSupport.identityOf(admin),
                IssueInvitationForProfileCommand.builder()
                    .recipientEmail(CONNECTED_EMAIL)
                    .householdRole(HouseholdRole.MEMBER)
                    .profileId(profile.getId())
                    .reofferHouseholdIds(List.of(reofferedTo.household().getId()))
                    .build())
            .fold(
                invitation -> invitation,
                rejection -> {
                  throw new IllegalStateException("The invitation was refused: " + rejection);
                });

    return accountInvitations
        .accept(
            AcceptInvitationCommand.builder()
                .code(issued.code())
                .displayName("Ollie")
                .password("a strong passphrase")
                .deviceName("web")
                .ipAddress(IP_ADDRESS)
                .build())
        .account()
        .getId();
  }

  private void lapse(ProfileHouseholdShare offer) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var lapsed = shareRepository.findById(offer.getId()).orElseThrow();
          lapsed.setExpiresAt(Instant.now().minusSeconds(1));
          shareRepository.saveAndFlush(lapsed);
        });
  }

  private ProfileHouseholdShare stored(ProfileHouseholdShare share) {
    return shareRepository.findById(share.getId()).orElseThrow();
  }

  private Optional<RequestResolution> resolutionOf(ProfileHouseholdShare share) {
    return RequestResolution.of(stored(share), Instant.now());
  }

  private List<Notification> notificationsAbout(ProfileHouseholdShare share) {
    return notificationRepository.findAll().stream()
        .filter(notification -> notification.getShareId().equals(share.getId()))
        .toList();
  }

  private List<Notification> answersAbout(ProfileHouseholdShare share) {
    return notificationsAbout(share).stream()
        .filter(notification -> notification.getKind() == NotificationKind.PROFILE_SHARE_ANSWERED)
        .toList();
  }

  private List<Notification> endingsAbout(ProfileHouseholdShare share) {
    return notificationsAbout(share).stream()
        .filter(notification -> notification.getKind() == NotificationKind.PROFILE_SHARE_ENDED)
        .toList();
  }

  private List<Notification> notificationsOf(AuthTestSupport.TestIdentity recipient) {
    return notificationsOf(recipient.account().getId());
  }

  private List<Notification> notificationsOf(UUID recipientAccountId) {
    return notificationRepository.findAll().stream()
        .filter(notification -> notification.getRecipientAccountId().equals(recipientAccountId))
        .toList();
  }

  /** A Profile of the owner's Household, available there, with the given direct managers. */
  private Profile profileManagedBy(
      String name, AuthTestSupport.TestIdentity owner, AuthTestSupport.TestIdentity... managers) {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .name(name)
                      .householdId(owner.household().getId())
                      .build());
          for (var manager : managers) {
            profileManagerRepository.saveAndFlush(
                ProfileManager.builder()
                    .accountId(manager.account().getId())
                    .profileId(profile.getId())
                    .build());
          }

          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(owner.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return profile;
        });
  }

  private AuthTestSupport.TestIdentity secondAdminOf(AuthTestSupport.TestIdentity household) {
    var member = track(authTestSupport.createHouseholdMemberIdentity(household));
    transactionTemplate.executeWithoutResult(
        _ -> userAccountRepository.tryPromoteToHouseholdAdmin(member.account().getId()));
    var promoted = userAccountRepository.findById(member.account().getId()).orElseThrow();
    assertThat(promoted.getHouseholdRole()).isEqualTo(HouseholdRole.ADMIN);
    return AuthTestSupport.TestIdentity.builder()
        .account(promoted)
        .household(member.household())
        .profile(member.profile())
        .session(member.session())
        .rawRefreshToken(member.rawRefreshToken())
        .build();
  }

  private AuthTestSupport.TestIdentity track(AuthTestSupport.TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
