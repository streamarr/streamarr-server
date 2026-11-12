package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.notification.NotificationForm;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.events.notification.NotificationKind;
import com.streamarr.server.services.events.notification.NotificationSnapshot;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.SubjectReference;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ShareSteps;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.InvitationReceivedSnapshot;
import com.streamarr.server.support.SpecifiedOnlyNotificationKinds.ManagementRemovedSnapshot;
import com.streamarr.server.support.StoredNotificationRows;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the identity domain dismisses when it deletes a Profile, a Household or an Account (ADR 0063
 * §Dismissal, §Deletion, disabling, and the end of a subject). The kinds that no domain tells yet
 * are told here as their domain will tell them, and the Notifications are read as they are stored.
 */
@Tag("IntegrationTest")
@DisplayName("Notification Dismissals Integration Tests")
class NotificationDismissalsIT extends AbstractIntegrationTest {

  @Autowired private ProfileSharingService sharing;
  @Autowired private ProfileAdministrationService profileAdministration;
  @Autowired private ProfileLifecycleService profileLifecycle;
  @Autowired private ProfileManagerAdministrationService profileManagement;
  @Autowired private HouseholdDeletionService householdDeletion;
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

    transactionTemplate.executeWithoutResult(_ -> eventPublisher.publishEvent(tell.build()));
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
