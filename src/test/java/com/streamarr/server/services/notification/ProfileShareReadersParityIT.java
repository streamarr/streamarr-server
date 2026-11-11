package com.streamarr.server.services.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareAnswer;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.identity.AccountAdministrationService;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The list and the count leave out, in SQL, the Notifications about a share that no read permit
 * could show their reader (ProfileShareReaders), and so state the share's read rule a second time
 * beside the Cedar permits (ADR 0063 §Consequences). For every kind of reader that the permits
 * allow, the filter keeps the reader's Notification, so the guard can show it. The filter may keep
 * one that the permits refuse, because the guard still decides it; for those readers this only
 * shows that the permits refuse them, so a permit that comes to allow one moves it to the readers
 * the filter must keep. A reader whose only tie is administering another Household that hosts the
 * Profile is left out by both, so neither can come to show such a reader a share into a Household
 * it does not administer.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Share Readers Parity Integration Tests")
class ProfileShareReadersParityIT extends AbstractIntegrationTest {

  @Autowired private ProfileSharingService sharing;
  @Autowired private ProfileManagerAdministrationService profileManagement;
  @Autowired private AccountAdministrationService accountAdministration;
  @Autowired private NotificationReads notificationReads;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<TestIdentity> identities = new ArrayList<>();
  private ShareSteps steps;
  private TestIdentity sam;
  private TestIdentity riverside;
  private Profile kai;
  private ProfileHouseholdShare share;

  @BeforeEach
  void setUp() {
    steps =
        ShareSteps.builder()
            .sharing(sharing)
            .auth(authTestSupport)
            .profiles(profileRepository)
            .managers(profileManagerRepository)
            .shares(shareRepository)
            .accounts(userAccountRepository)
            .transactions(transactionTemplate)
            .build();
    sam = track(authTestSupport.createIdentity());
    riverside = track(authTestSupport.createIdentity());
    kai = steps.profileManagedBy("Kai", sam, sam);
    share = steps.accept(riverside, steps.offer(sam, kai, riverside));
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("readersThePermitsAllow")
  @DisplayName(
      "Should keep the reader's Notification in the list and the count when a read permit allows"
          + " the reader")
  void shouldKeepTheReadersNotificationInTheListAndTheCountWhenAReadPermitAllowsTheReader(
      String reader, Arrangement arrangement) {
    var reading = arrangement.arrange(this);
    var notification = newsAbout(reading);

    assertThat(guardOf(notification)).isEqualTo(NotificationVisibility.SHOWN);
    assertThat(
            notificationRepository.rowsOfRecipientWithIds(
                reading.readerId(), List.of(notification.getId())))
        .hasSize(1);
    assertThat(notificationRepository.findUnreadOfRecipient(reading.readerId(), Instant.now()))
        .extracting(Notification::getId)
        .contains(notification.getId());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("readersThePermitsRefuse")
  @DisplayName("Should hide the reader's Notification when every read permit refuses the reader")
  void shouldHideTheReadersNotificationWhenEveryReadPermitRefusesTheReader(
      String reader, Arrangement arrangement) {
    var notification = newsAbout(arrangement.arrange(this));

    assertThat(guardOf(notification)).isEqualTo(NotificationVisibility.HIDDEN);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("readersWhoOnlyHostTheProfile")
  @DisplayName(
      "Should leave the reader's Notification out of the list and the count when the reader only"
          + " administers another Household that hosts the Profile")
  void shouldLeaveTheReadersNotificationOutWhenTheReaderOnlyAdministersAnotherHostingHousehold(
      String reader, Arrangement arrangement) {
    var reading = arrangement.arrange(this);
    var notification = newsAbout(reading);

    assertThat(guardOf(notification)).isEqualTo(NotificationVisibility.HIDDEN);
    assertThat(
            notificationRepository.rowsOfRecipientWithIds(
                reading.readerId(), List.of(notification.getId())))
        .isEmpty();
    assertThat(notificationRepository.findUnreadOfRecipient(reading.readerId(), Instant.now()))
        .extracting(Notification::getId)
        .doesNotContain(notification.getId());
  }

  static Stream<Arguments> readersThePermitsAllow() {
    return Stream.of(
        reader(
            "a HouseholdAdmin of the target Household",
            ProfileShareReadersParityIT::targetHouseholdAdmin),
        reader(
            "a HouseholdAdmin of the target Household once the share has ended",
            ProfileShareReadersParityIT::targetHouseholdAdminOnceTheShareEnded),
        reader("a direct ProfileManager", ProfileShareReadersParityIT::directManager),
        reader(
            "the Account of a self-managed Personal Profile",
            ProfileShareReadersParityIT::selfManagedPersonalProfileAccount),
        reader(
            "the Account of a Personal Profile once its supervision is lifted",
            ProfileShareReadersParityIT::personalProfileAccountOnceSupervisionIsLifted),
        reader("a ServerAdmin", ProfileShareReadersParityIT::serverAdmin),
        reader(
            "a HouseholdAdmin of the target Household once enabled again",
            ProfileShareReadersParityIT::targetHouseholdAdminOnceEnabledAgain));
  }

  static Stream<Arguments> readersThePermitsRefuse() {
    return Stream.of(
        reader(
            "a HouseholdAdmin of the target Household once demoted",
            ProfileShareReadersParityIT::targetHouseholdAdminOnceDemoted),
        reader(
            "a HouseholdAdmin of another Household once the Profile's share into it has ended",
            ProfileShareReadersParityIT::hostingHouseholdAdminOnceItsShareEnded),
        reader(
            "a direct ProfileManager once it relinquished management",
            ProfileShareReadersParityIT::directManagerOnceItRelinquished),
        reader(
            "the Account of a self-managed Personal Profile once the Profile is supervised",
            ProfileShareReadersParityIT::selfManagedPersonalProfileAccountOnceSupervised),
        reader(
            "the Account of a supervised Personal Profile",
            ProfileShareReadersParityIT::supervisedPersonalProfileAccount),
        reader(
            "a ServerAdmin once its authority is revoked",
            ProfileShareReadersParityIT::serverAdminOnceRevoked),
        reader(
            "a plain member of the target Household",
            ProfileShareReadersParityIT::targetHouseholdMember),
        reader(
            "a plain member of the Profile's home Household",
            ProfileShareReadersParityIT::homeHouseholdMember),
        reader("an unrelated Account", ProfileShareReadersParityIT::unrelatedAccount),
        reader(
            "a disabled HouseholdAdmin of the target Household",
            ProfileShareReadersParityIT::disabledTargetHouseholdAdmin));
  }

  static Stream<Arguments> readersWhoOnlyHostTheProfile() {
    return Stream.of(
        reader(
            "a HouseholdAdmin of the Profile's home Household",
            ProfileShareReadersParityIT::homeHouseholdAdmin),
        reader(
            "a HouseholdAdmin of another Household the Profile is shared into",
            ProfileShareReadersParityIT::hostingHouseholdAdmin),
        reader(
            "the Account that made the offer once it stopped managing the Profile, while it"
                + " administers another Household the Profile is shared into",
            ProfileShareReadersParityIT::offererOnceItStoppedManagingWhileItHostsTheProfile));
  }

  private static Arguments reader(String description, Arrangement arrangement) {
    return Arguments.of(description, arrangement);
  }

  /** Arranges one kind of reader in the database. */
  @FunctionalInterface
  private interface Arrangement {
    Reading arrange(ProfileShareReadersParityIT test);
  }

  /** A reader, and the share that its Notification is about. */
  private record Reading(UUID readerId, ProfileHouseholdShare share) {}

  private Reading targetHouseholdAdmin() {
    return new Reading(riverside.account().getId(), share);
  }

  private Reading targetHouseholdAdminOnceTheShareEnded() {
    assertThat(sharing.endProfileShare(authTestSupport.identityOf(riverside), share.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    return new Reading(riverside.account().getId(), share);
  }

  private Reading targetHouseholdAdminOnceDemoted() {
    var admin = steps.promoted(track(authTestSupport.createHouseholdMemberIdentity(riverside)));
    transactionTemplate.executeWithoutResult(
        _ -> userAccountRepository.tryDemoteToHouseholdMember(admin.account().getId()));
    return new Reading(admin.account().getId(), share);
  }

  private Reading disabledTargetHouseholdAdmin() {
    var admin = steps.promoted(track(authTestSupport.createHouseholdMemberIdentity(riverside)));
    enable(admin, false);
    return new Reading(admin.account().getId(), share);
  }

  private Reading targetHouseholdAdminOnceEnabledAgain() {
    var admin = steps.promoted(track(authTestSupport.createHouseholdMemberIdentity(riverside)));
    enable(admin, false);
    enable(admin, true);
    return new Reading(admin.account().getId(), share);
  }

  private Reading homeHouseholdAdmin() {
    var admin = steps.promoted(track(authTestSupport.createHouseholdMemberIdentity(sam)));
    return new Reading(admin.account().getId(), share);
  }

  private Reading hostingHouseholdAdmin() {
    var brook = track(authTestSupport.createIdentity());
    steps.accept(brook, steps.offer(sam, kai, brook));
    return new Reading(brook.account().getId(), share);
  }

  private Reading offererOnceItStoppedManagingWhileItHostsTheProfile() {
    var brook = track(authTestSupport.createIdentity());
    grantManagementOfKai(brook);
    steps.accept(brook, steps.offer(sam, kai, brook));
    var willow = track(authTestSupport.createIdentity());
    var offered = steps.accept(willow, steps.offer(brook, kai, willow));
    assertThat(
            profileManagement.relinquishProfileManagement(
                authTestSupport.identityOf(brook), kai.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    return new Reading(brook.account().getId(), offered);
  }

  private Reading hostingHouseholdAdminOnceItsShareEnded() {
    var brook = track(authTestSupport.createIdentity());
    var visit = steps.accept(brook, steps.offer(sam, kai, brook));
    assertThat(sharing.endProfileShare(authTestSupport.identityOf(brook), visit.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    return new Reading(brook.account().getId(), share);
  }

  private Reading directManager() {
    var mira = track(authTestSupport.createIdentity());
    grantManagementOfKai(mira);
    return new Reading(mira.account().getId(), share);
  }

  private Reading directManagerOnceItRelinquished() {
    var mira = track(authTestSupport.createIdentity());
    grantManagementOfKai(mira);
    assertThat(
            profileManagement.relinquishProfileManagement(
                authTestSupport.identityOf(mira), kai.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    return new Reading(mira.account().getId(), share);
  }

  private Reading selfManagedPersonalProfileAccount() {
    var olive = track(authTestSupport.createHouseholdMemberIdentity(sam));
    var visit = steps.accept(riverside, steps.offer(olive, olive.profile(), riverside));
    return new Reading(olive.account().getId(), visit);
  }

  private Reading selfManagedPersonalProfileAccountOnceSupervised() {
    var olive = track(authTestSupport.createHouseholdMemberIdentity(sam));
    var visit = steps.accept(riverside, steps.offer(olive, olive.profile(), riverside));
    supervise(olive);
    return new Reading(olive.account().getId(), visit);
  }

  private Reading supervisedPersonalProfileAccount() {
    var kit = track(authTestSupport.createHouseholdMemberIdentity(sam));
    supervise(kit);
    var visit = steps.accept(riverside, steps.offer(sam, kit.profile(), riverside));
    return new Reading(kit.account().getId(), visit);
  }

  private Reading personalProfileAccountOnceSupervisionIsLifted() {
    var kit = track(authTestSupport.createHouseholdMemberIdentity(sam));
    supervise(kit);
    var visit = steps.accept(riverside, steps.offer(sam, kit.profile(), riverside));
    setKind(kit.profile(), ProfileKind.ADULT);
    return new Reading(kit.account().getId(), visit);
  }

  private Reading serverAdmin() {
    var sol = track(authTestSupport.createAdminIdentity());
    return new Reading(sol.account().getId(), share);
  }

  private Reading serverAdminOnceRevoked() {
    var sol = track(authTestSupport.createAdminIdentity());
    var revoker = track(authTestSupport.createAdminIdentity());
    assertThat(
            accountAdministration.revokeServerAdmin(
                authTestSupport.freshIdentityOf(revoker), sol.account().getId(), "rotation"))
        .isInstanceOf(Outcome.Accepted.class);
    return new Reading(sol.account().getId(), share);
  }

  private Reading targetHouseholdMember() {
    var rowan = track(authTestSupport.createHouseholdMemberIdentity(riverside));
    return new Reading(rowan.account().getId(), share);
  }

  private Reading homeHouseholdMember() {
    var theo = track(authTestSupport.createHouseholdMemberIdentity(sam));
    return new Reading(theo.account().getId(), share);
  }

  private Reading unrelatedAccount() {
    var uma = track(authTestSupport.createIdentity());
    return new Reading(uma.account().getId(), share);
  }

  private void grantManagementOfKai(TestIdentity manager) {
    transactionTemplate.executeWithoutResult(
        _ ->
            profileManagerRepository.tryGrantDirectManagement(
                manager.account().getId(), kai.getId()));
  }

  /** The member's Personal Profile becomes a Kid Profile, supervised by Sam. */
  private void supervise(TestIdentity member) {
    transactionTemplate.executeWithoutResult(
        _ ->
            profileManagerRepository.tryGrantDirectManagement(
                sam.account().getId(), member.profile().getId()));
    setKind(member.profile(), ProfileKind.KID);
  }

  private void setKind(Profile personal, ProfileKind kind) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var profile = profileRepository.findById(personal.getId()).orElseThrow();
          profile.setKind(kind);
          profileRepository.saveAndFlush(profile);
        });
  }

  private void enable(TestIdentity identity, boolean enabled) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
          account.setEnabled(enabled);
          userAccountRepository.saveAndFlush(account);
        });
  }

  /** Unread news about the share for the reader, stored as the writer stores news. */
  private Notification newsAbout(Reading reading) {
    var notificationId =
        transactionTemplate.execute(
            _ ->
                notificationRepository
                    .upsertUnreadNews(
                        NotificationDraft.<NotificationKind, ProfileShareFacts>builder()
                            .recipientAccountId(reading.readerId())
                            .kind(NotificationKind.PROFILE_SHARE_ANSWERED)
                            .subjectId(reading.share().getId())
                            .facts(ProfileShareFacts.answered("Kai", ProfileShareAnswer.ACCEPTED))
                            .at(Instant.now())
                            .build())
                    .notificationId());
    return notificationRepository.findById(notificationId).orElseThrow();
  }

  private NotificationVisibility guardOf(Notification notification) {
    return notificationReads.visibilities(List.of(notification)).get(notification.getId());
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
