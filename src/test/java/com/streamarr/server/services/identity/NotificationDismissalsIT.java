package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.domain.notification.ProfileShareAnswer;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.events.notification.NotificationKind;
import com.streamarr.server.services.events.notification.NotificationSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareAnsweredSnapshot;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import com.streamarr.server.services.events.notification.SubjectReference;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.identity.AccountLifecycleService.AdministrativelyDeleteAccountCommand;
import com.streamarr.server.services.identity.AccountLifecycleService.ProfileCleanup;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.TransferLastAccountAndDeleteHouseholdCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.notification.events.NotificationsChangedEvent;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ShareSteps;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.HouseholdNewsSnapshot;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.InvitationReceivedSnapshot;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.ManagementRemovedSnapshot;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.StoredNotificationRows.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the identity domain dismisses when it deletes a Profile, a Household or an Account (ADR 0063
 * §Dismissal, §Deletion, disabling, and the end of a subject). The kinds that no domain tells yet
 * are told here as their domain will tell them, and the Notifications are read as they are stored.
 */
@Tag("IntegrationTest")
@DisplayName("Notification Dismissals Integration Tests")
@RecordApplicationEvents
class NotificationDismissalsIT extends AbstractIntegrationTest {

  @Autowired private ProfileSharingService sharing;
  @Autowired private ProfileAdministrationService profileAdministration;
  @Autowired private ProfileLifecycleService profileLifecycle;
  @Autowired private ProfileManagerAdministrationService profileManagement;
  @Autowired private HouseholdDeletionService householdDeletion;
  @Autowired private AccountLifecycleService accountLifecycle;
  @Autowired private ApplicationEvents applicationEvents;
  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private DSLContext dsl;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity sam;
  private TestIdentity theo;
  private TestIdentity serverAdmin;
  private ShareSteps steps;
  private StoredNotificationRows rows;

  @BeforeEach
  void setUp() {
    rows = new StoredNotificationRows(dsl);
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
    theo = track(authTestSupport.createIdentity());
    serverAdmin = track(authTestSupport.createAdminIdentity());
  }

  @AfterEach
  void tearDown() {
    rows.deleteOf(identities.stream().map(NotificationDismissalsIT::accountOf).toList());
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @ParameterizedTest
  @EnumSource(ProfileDeletion.class)
  @DisplayName(
      "Should dismiss the Notifications about a Profile and its invitations when the Profile is"
          + " deleted")
  void shouldDismissTheNotificationsAboutAProfileAndItsInvitationsWhenTheProfileIsDeleted(
      ProfileDeletion deletion) {
    var ollie = steps.unsharedProfileManagedBy("Ollie", sam, sam);
    var kai = steps.unsharedProfileManagedBy("Kai", theo, theo);
    var invitation = SubjectReference.profileManagerInvitation(invite(theo, ollie));
    tell(
        SpecifiedOnlyNotificationKinds.MANAGEMENT_REMOVED,
        SubjectReference.profile(ollie.getId()),
        new ManagementRemovedSnapshot("Ollie"));
    tell(
        SpecifiedOnlyNotificationKinds.INVITATION_RECEIVED,
        invitation,
        new InvitationReceivedSnapshot("Ollie", Optional.empty()));
    tell(
        SpecifiedOnlyNotificationKinds.MANAGEMENT_REMOVED,
        SubjectReference.profile(kai.getId()),
        new ManagementRemovedSnapshot("Kai"));

    var outcome = delete(ollie, deletion);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(rows.about(SubjectReference.profile(ollie.getId()))).isEmpty();
    assertThat(rows.about(invitation)).isEmpty();
    assertThat(rows.about(SubjectReference.profile(kai.getId()))).hasSize(1);
    assertThat(invitationRepository.findById(invitation.id())).isPresent();
  }

  /** How a Profile is deleted. */
  enum ProfileDeletion {
    BY_ITS_SOLE_MANAGER,
    BY_A_SERVER_ADMIN,
    WITH_ITS_HOUSEHOLD
  }

  @ParameterizedTest
  @EnumSource(HouseholdDeletion.class)
  @DisplayName("Should dismiss the Notifications about a Household when it is deleted")
  void shouldDismissTheNotificationsAboutAHouseholdWhenItIsDeleted(HouseholdDeletion deletion) {
    var lakeside = track(authTestSupport.createIdentity());
    var deleted = SubjectReference.household(lakeside.household().getId());
    var kept = SubjectReference.household(sam.household().getId());
    tell(
        SpecifiedOnlyNotificationKinds.HOUSEHOLD_NEWS,
        deleted,
        new HouseholdNewsSnapshot("Lakeside"));
    tell(SpecifiedOnlyNotificationKinds.HOUSEHOLD_NEWS, kept, new HouseholdNewsSnapshot("Sam's"));

    var outcome = delete(lakeside.household().getId(), deletion);

    assertThat(outcome).isEqualTo(Outcome.accepted(lakeside.household().getId()));
    assertThat(rows.about(deleted)).isEmpty();
    assertThat(rows.about(kept)).hasSize(1);
  }

  @ParameterizedTest
  @EnumSource(AccountDeletion.class)
  @DisplayName(
      "Should delete the Account's Notifications and no other Account's when the Account is"
          + " deleted")
  void shouldDeleteTheAccountsNotificationsAndNoOtherAccountsWhenTheAccountIsDeleted(
      AccountDeletion deletion) {
    var brook = track(deletion.departing(authTestSupport, sam));
    var share = SubjectReference.profileShare(UUID.randomUUID());
    tellAnswer(share, brook, theo);

    var outcome = delete(brook, deletion);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(rows.of(accountOf(brook))).isEmpty();
    assertThat(rows.of(accountOf(theo))).extracting(Row::subject).containsExactly(share);
  }

  /** How an Account is deleted. */
  enum AccountDeletion {
    BY_ITSELF,
    BY_A_SERVER_ADMIN,
    WITH_ITS_HOUSEHOLD;

    // Only the deletion of its Household deletes a Household's final Account.
    TestIdentity departing(AuthTestSupport auth, TestIdentity householdOf) {
      return switch (this) {
        case BY_ITSELF, BY_A_SERVER_ADMIN -> auth.createHouseholdMemberIdentity(householdOf);
        case WITH_ITS_HOUSEHOLD -> auth.createIdentity();
      };
    }
  }

  private Outcome<?, ?> delete(TestIdentity account, AccountDeletion deletion) {
    return switch (deletion) {
      case BY_ITSELF ->
          accountLifecycle.deleteMyAccount(authTestSupport.freshIdentityOf(account), "DELETE");
      case BY_A_SERVER_ADMIN ->
          accountLifecycle.administrativelyDeleteAccount(
              authTestSupport.freshIdentityOf(serverAdmin),
              AdministrativelyDeleteAccountCommand.builder()
                  .accountId(account.account().getId())
                  .profileCleanup(ProfileCleanup.ERASE_PROFILE)
                  .reason("left")
                  .build());
      case WITH_ITS_HOUSEHOLD ->
          delete(account.household().getId(), HouseholdDeletion.DELETE_ITS_LAST_ACCOUNT);
    };
  }

  @Test
  @DisplayName(
      "Should keep the Notifications about a share when the Account that made the offer is"
          + " deleted")
  void shouldKeepTheNotificationsAboutAShareWhenTheAccountThatMadeTheOfferIsDeleted() {
    var brook = track(authTestSupport.createHouseholdMemberIdentity(sam));
    var offer = steps.offer(brook, steps.profileManagedBy("Kai", sam, sam, brook), theo);

    var outcome =
        accountLifecycle.deleteMyAccount(authTestSupport.freshIdentityOf(brook), "DELETE");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(rows.about(SubjectReference.profileShare(offer.getId())))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.recipient()).isEqualTo(accountOf(theo));
              assertThat(request.resolution()).contains("WITHDRAWN");
            });
  }

  @Test
  @DisplayName("Should push nothing when a recipient Account is deleted")
  void shouldPushNothingWhenARecipientAccountIsDeleted() {
    var brook = track(authTestSupport.createHouseholdMemberIdentity(sam));
    tellAnswer(SubjectReference.profileShare(UUID.randomUUID()), brook, theo);
    applicationEvents.clear();

    var outcome =
        accountLifecycle.deleteMyAccount(authTestSupport.freshIdentityOf(brook), "DELETE");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(rows.of(accountOf(brook))).isEmpty();
    assertThat(applicationEvents.stream(NotificationsChangedEvent.class)).isEmpty();
  }

  /** What becomes of the final Account of a Household being deleted. */
  enum HouseholdDeletion {
    TRANSFER_ITS_LAST_ACCOUNT,
    DELETE_ITS_LAST_ACCOUNT,
    DELETE_ITS_LAST_ACCOUNT_KEEPING_ITS_PROFILE
  }

  private Outcome<?, ?> delete(UUID householdId, HouseholdDeletion deletion) {
    var identity = authTestSupport.freshIdentityOf(serverAdmin);
    var destinationId = serverAdmin.household().getId();
    return switch (deletion) {
      case TRANSFER_ITS_LAST_ACCOUNT ->
          householdDeletion.transferLastAccountAndDeleteHousehold(
              identity,
              TransferLastAccountAndDeleteHouseholdCommand.builder()
                  .householdId(householdId)
                  .destinationHouseholdId(destinationId)
                  .reason("closing")
                  .build());
      case DELETE_ITS_LAST_ACCOUNT ->
          householdDeletion.deleteLastAccountAndHousehold(
              identity,
              DeleteLastAccountAndHouseholdCommand.builder()
                  .householdId(householdId)
                  .reason("closing")
                  .build());
      case DELETE_ITS_LAST_ACCOUNT_KEEPING_ITS_PROFILE ->
          householdDeletion.deleteLastAccountAndHouseholdPreservingPersonalProfile(
              identity,
              DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand.builder()
                  .householdId(householdId)
                  .destinationHouseholdId(destinationId)
                  .replacementManagerAccountId(serverAdmin.account().getId())
                  .reason("closing")
                  .build());
    };
  }

  private Outcome<?, ?> delete(Profile profile, ProfileDeletion deletion) {
    return switch (deletion) {
      case BY_ITS_SOLE_MANAGER ->
          profileAdministration.deleteProfile(
              authTestSupport.freshIdentityOf(sam), profile.getId());
      case BY_A_SERVER_ADMIN ->
          profileLifecycle.administrativelyDeleteProfile(
              authTestSupport.freshIdentityOf(serverAdmin), profile.getId(), "a duplicate");
      case WITH_ITS_HOUSEHOLD ->
          householdDeletion.deleteLastAccountAndHousehold(
              authTestSupport.freshIdentityOf(serverAdmin),
              DeleteLastAccountAndHouseholdCommand.builder()
                  .householdId(profile.getHouseholdId())
                  .reason("closing")
                  .build());
    };
  }

  /** An invitation for the Account to manage the Profile, issued by the Profile's manager Sam. */
  private UUID invite(TestIdentity invited, Profile profile) {
    return profileManagement
        .inviteProfileManager(
            authTestSupport.identityOf(sam), profile.getId(), invited.account().getId())
        .fold(
            issued -> issued.invitation().getId(),
            rejection -> {
              throw new IllegalStateException("The invitation was refused: " + rejection);
            });
  }

  /** Tells the recipients that an offer of Kai, the share, was accepted. */
  private void tellAnswer(SubjectReference share, TestIdentity... recipients) {
    publish(
        TellNotificationEvent.builder()
            .kind(SharingNotificationKinds.PROFILE_SHARE_ANSWERED)
            .subject(share)
            .recipients(
                Stream.of(recipients)
                    .map(NotificationDismissalsIT::accountOf)
                    .collect(Collectors.toSet()))
            .snapshot(new ProfileShareAnsweredSnapshot("Kai", ProfileShareAnswer.ACCEPTED))
            .at(Instant.now())
            .build());
  }

  private void publish(Object event) {
    transactionTemplate.executeWithoutResult(_ -> eventPublisher.publishEvent(event));
  }

  /** Tells Theo of the subject, as the kind's domain will. */
  private void tell(
      NotificationKind kind, SubjectReference subject, NotificationSnapshot snapshot) {
    var at = Instant.now();
    var tell =
        TellNotificationEvent.builder()
            .kind(kind)
            .subject(subject)
            .recipients(Set.of(accountOf(theo)))
            .snapshot(snapshot)
            .at(at);
    if (kind.form() == NotificationForm.REQUEST) {
      tell.subjectExpiresAt(Optional.of(at.plus(Duration.ofDays(7))));
    }

    publish(tell.build());
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
