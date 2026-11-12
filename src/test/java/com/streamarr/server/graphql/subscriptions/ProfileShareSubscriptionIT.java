package com.streamarr.server.graphql.subscriptions;

import static com.streamarr.server.support.GraphQlResults.firstErrorCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.ProfileShareExpirySweeper;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlHttpTestClient;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Each committed change of a Profile share reaches every open session that may read the share, with
 * the fields the share queries show that reader, and no other session.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Share Subscription Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfileShareSubscriptionIT extends AbstractIntegrationTest {

  private static final String FIELD = "profileShareChanged";
  private static final String OPERATION = "shares";
  private static final String SUBSCRIPTION =
      """
      subscription {
        profileShareChanged {
          shareId
          share {
            id status profileId householdId invalidationReason
            profile { id name } visitor offeredByDisplayName household { id name }
          }
        }
      }
      """;

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private ProfileShareExpirySweeper expirySweeper;
  @Autowired private HouseholdRepository householdRepository;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private final List<GraphQlTransportWsTestClient> sockets = new ArrayList<>();
  private final List<UUID> households = new ArrayList<>();
  private ActiveSubscriptions activeSubscriptions;
  private GraphQlHttpTestClient http;
  private AuthTestSupport.TestIdentity owner;
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void openClients() {
    activeSubscriptions = new ActiveSubscriptions(meterRegistry);
    http = new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)));
    owner = identity();
    host = identity();
  }

  @AfterEach
  void releaseResources() {
    sockets.forEach(GraphQlTransportWsTestClient::close);
    http.close();
    activeSubscriptions.awaitNoneActive();
    transactionTemplate.executeWithoutResult(
        _ ->
            households.forEach(
                id -> householdRepository.findById(id).ifPresent(householdRepository::delete)));
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should deliver the offer, acceptance and end to both sides when a share runs its course")
  void shouldDeliverTheOfferAcceptanceAndEndToBothSidesWhenAShareRunsItsCourse() {
    var hostSocket = subscribedSocket(authTestSupport.accountBearer(host));
    var ownerSocket = subscribedSocket(authTestSupport.accountBearer(owner));
    var orphan = managedProfile();

    var shareId = offer(orphan);
    var offeredToHost = nextShare(hostSocket, shareId);
    var offeredToOwner = nextShare(ownerSocket, shareId);
    decide("acceptProfileShare", shareId);
    var acceptedForHost = nextShare(hostSocket, shareId);
    var acceptedForOwner = nextShare(ownerSocket, shareId);
    decide("endProfileShare", shareId);
    var endedForHost = nextShare(hostSocket, shareId);
    var endedForOwner = nextShare(ownerSocket, shareId);

    assertThat(List.of(offeredToHost, acceptedForHost, endedForHost))
        .extracting(share -> share.path("status").asString())
        .containsExactly("PENDING", "ACTIVE", "ENDED");
    assertThat(List.of(offeredToOwner, acceptedForOwner, endedForOwner))
        .extracting(share -> share.path("status").asString())
        .containsExactly("PENDING", "ACTIVE", "ENDED");
    assertThat(List.of(offeredToHost, acceptedForHost))
        .allSatisfy(
            share -> {
              assertThat(share.path("profile").path("name").asString()).isEqualTo(orphan.getName());
              assertThat(share.path("offeredByDisplayName").asString())
                  .isEqualTo(owner.account().getDisplayName());
            });
    // The Household's name belongs to the Profile's managers and ServerAdmins.
    assertThat(offeredToHost.path("household").isNull()).isTrue();
    // Once the share has ended, its Household no longer sees the Profile or who offered it.
    assertPartiesHidden(endedForHost);
    assertThat(List.of(offeredToOwner, endedForOwner))
        .allSatisfy(
            share -> {
              assertThat(share.path("profile").path("name").asString()).isEqualTo(orphan.getName());
              assertThat(share.path("household").path("name").asString())
                  .isEqualTo(host.household().getName());
              // The offerer's name belongs to the target side, as in the share queries.
              assertThat(share.path("offeredByDisplayName").isNull()).isTrue();
            });
  }

  @Test
  @DisplayName(
      "Should tell a visitor from the Household's own Profile when a change reaches those who see"
          + " the Profile")
  void shouldTellVisitorFromHouseholdsOwnProfileWhenChangeReachesThoseWhoSeeProfile() {
    var hostSocket = subscribedSocket(authTestSupport.accountBearer(host));
    var ownerSocket = subscribedSocket(authTestSupport.accountBearer(owner));

    var visitId = offer(managedProfile());
    var visitForHost = nextShare(hostSocket, visitId);
    var visitForOwner = nextShare(ownerSocket, visitId);
    var ownId = createProfileAtHome(host);
    var ownForHost = nextShare(hostSocket, ownId);

    assertThat(List.of(visitForHost, visitForOwner, ownForHost))
        .allSatisfy(share -> assertThat(share.path("profile").isObject()).isTrue());
    assertThat(visitForHost.path("visitor").isBoolean()).isTrue();
    assertThat(visitForHost.path("visitor").asBoolean()).isTrue();
    assertThat(visitForOwner.path("visitor").asBoolean()).isTrue();
    assertThat(ownForHost.path("visitor").isBoolean()).isTrue();
    assertThat(ownForHost.path("visitor").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("Should deliver a declined offer and a canceled offer when either side turns down")
  void shouldDeliverADeclinedOfferAndACanceledOfferWhenEitherSideTurnsDown() {
    var hostSocket = subscribedSocket(authTestSupport.accountBearer(host));
    var ownerSocket = subscribedSocket(authTestSupport.accountBearer(owner));
    var orphan = managedProfile();

    var declinedId = offer(orphan);
    decide("rejectProfileShare", declinedId);
    var declinedForOwner = nextShareWithStatus(ownerSocket, declinedId, "REJECTED");
    var declinedForHost = nextShareWithStatus(hostSocket, declinedId, "REJECTED");
    var canceledId = offer(orphan);
    cancel(canceledId);
    var canceledForHost = nextShareWithStatus(hostSocket, canceledId, "CANCELED");
    var canceledForOwner = nextShareWithStatus(ownerSocket, canceledId, "CANCELED");

    assertThat(List.of(declinedForOwner, canceledForOwner))
        .allSatisfy(
            share -> {
              assertThat(share.path("profile").path("name").asString()).isEqualTo(orphan.getName());
              assertThat(share.path("household").path("name").asString())
                  .isEqualTo(host.household().getName());
            });
    // The Household still hears that the offer is over, so its client can take the row away.
    assertPartiesHidden(declinedForHost);
    assertPartiesHidden(canceledForHost);
  }

  @Test
  @DisplayName("Should deliver the withdrawn offer when the system invalidates it")
  void shouldDeliverTheWithdrawnOfferWhenTheSystemInvalidatesIt() {
    var hostSocket = subscribedSocket(authTestSupport.accountBearer(host));
    var shareId = offer(managedProfile());
    nextShare(hostSocket, shareId);
    var serverAdmin = adminIdentity();

    var disabled =
        http.execute(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { disableAccount(input: {accountId: "%s"}) { userErrors { __typename } } }
            """
                .formatted(owner.account().getId()));
    var invalidated = nextShare(hostSocket, shareId);

    assertThat(disabled.path("errors").isMissingNode()).as("errors in %s", disabled).isTrue();
    assertThat(invalidated.path("status").asString()).isEqualTo("INVALIDATED");
    assertPartiesHidden(invalidated);
  }

  @Test
  @DisplayName(
      "Should tell only the Profile's side why the offer was withdrawn when its offerer stops"
          + " managing the Profile")
  void shouldTellOnlyTheProfilesSideWhyTheOfferWasWithdrawnWhenItsOffererStopsManagingTheProfile() {
    var manager = authTestSupport.createHouseholdMemberIdentity(owner);
    identities.add(manager);
    var profile = managedProfile();
    grantManagement(manager, profile);
    var hostSocket = subscribedSocket(authTestSupport.accountBearer(host));
    var managerSocket = subscribedSocket(authTestSupport.accountBearer(manager));
    var shareId = offer(profile);
    nextShare(hostSocket, shareId);
    nextShare(managerSocket, shareId);

    var relinquished =
        http.execute(
            authTestSupport.accountBearer(owner),
            """
            mutation { relinquishProfileManagement(input: {profileId: "%s"}) {
              profileId userErrors { __typename } } }
            """
                .formatted(profile.getId()));
    var forHost = nextShare(hostSocket, shareId);
    var forManager = nextShare(managerSocket, shareId);

    assertThat(relinquished.at("/data/relinquishProfileManagement/userErrors").isEmpty())
        .as("relinquish in %s", relinquished)
        .isTrue();
    assertThat(forHost.path("status").asString()).isEqualTo("INVALIDATED");
    // The reason tells what became of the offerer, which the Household no longer sees.
    assertPartiesHidden(forHost);
    assertThat(forManager.path("status").asString()).isEqualTo("INVALIDATED");
    assertThat(forManager.path("invalidationReason").asString())
        .isEqualTo("offering manager lost management");
  }

  @Test
  @DisplayName(
      "Should deliver the withdrawn offer when its acceptance finds the offerer no longer"
          + " authorized")
  void shouldDeliverTheWithdrawnOfferWhenItsAcceptanceFindsTheOffererNoLongerAuthorized() {
    var hostSocket = subscribedSocket(authTestSupport.accountBearer(host));
    var shareId = offer(managedProfile());
    nextShare(hostSocket, shareId);
    // Disabled in the database alone, so the offer is still pending when the host accepts it.
    var offerer = userAccountRepository.findById(owner.account().getId()).orElseThrow();
    offerer.setEnabled(false);
    userAccountRepository.saveAndFlush(offerer);

    var accepted =
        http.execute(
            authTestSupport.accountBearer(host),
            """
            mutation { acceptProfileShare(input: {shareId: "%s"}) { userErrors { __typename } } }
            """
                .formatted(shareId));
    var withdrawn = nextShare(hostSocket, shareId);

    assertThat(accepted.at("/data/acceptProfileShare/userErrors/0/__typename").asString())
        .as("acceptance in %s", accepted)
        .isEqualTo("OfferInvalidatedError");
    assertThat(withdrawn.path("status").asString()).isEqualTo("INVALIDATED");
    assertPartiesHidden(withdrawn);
  }

  @Test
  @DisplayName("Should deliver EXPIRED when an offer's time runs out before anyone decides")
  void shouldDeliverExpiredWhenAnOffersTimeRunsOutBeforeAnyoneDecides() {
    var hostSocket = subscribedSocket(authTestSupport.accountBearer(host));
    var shareId = offer(managedProfile());
    nextShare(hostSocket, shareId);
    var offer = shareRepository.findById(UUID.fromString(shareId)).orElseThrow();
    offer.setExpiresAt(Instant.now().minusSeconds(1));
    shareRepository.saveAndFlush(offer);

    expirySweeper.sweep();

    var expired = nextShare(hostSocket, shareId);
    assertThat(expired.path("status").asString()).isEqualTo("EXPIRED");
    assertPartiesHidden(expired);
    assertThat(shareRepository.findById(offer.getId()).orElseThrow().getLastModifiedBy())
        .isEqualTo(SystemActor.ID);
  }

  @Test
  @DisplayName(
      "Should deliver the removal of an active share to its Household when its Profile is deleted")
  void shouldDeliverTheRemovalOfAnActiveShareToItsHouseholdWhenItsProfileIsDeleted() {
    var orphan = managedProfile();
    var shareId = offer(orphan);
    decide("acceptProfileShare", shareId);
    var hostSocket = subscribedSocket(authTestSupport.accountBearer(host));
    var ownerSocket = subscribedSocket(authTestSupport.accountBearer(owner));
    var stranger = identity();
    var strangerSocket = subscribedSocket(authTestSupport.accountBearer(stranger));
    var serverAdmin = adminIdentity();

    // A ServerAdmin may delete a Profile that is still shared; its shares go with it.
    var deleted =
        http.execute(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { administrativelyDeleteProfile(input: {profileId: "%s", reason: "closing"}) {
              profileId userErrors { __typename } } }
            """
                .formatted(orphan.getId()));
    assertThat(deleted.at("/data/administrativelyDeleteProfile/profileId").asString())
        .as("deletion in %s", deleted)
        .isEqualTo(orphan.getId().toString());
    var removal = nextRemoval(hostSocket, shareId);
    var visibleId = offer(managedProfile(), stranger);

    assertThat(removal.path("shareId").asString()).isEqualTo(shareId);
    // The managers lose the Profile with it: they get no removal, and their operation stays open.
    assertThat(changesUntil(ownerSocket, visibleId))
        .extracting(
            change -> change.path("shareId").asString(), change -> change.path("share").isNull())
        .isNotEmpty()
        .doesNotContain(tuple(shareId, true));
    assertThat(changesUntil(strangerSocket, visibleId))
        .extracting(change -> change.path("shareId").asString())
        .isNotEmpty()
        .doesNotContain(shareId);
  }

  @Test
  @DisplayName(
      "Should deliver the removal to a ServerAdmin and not to the Household when the Profile of a"
          + " declined offer is deleted")
  void shouldDeliverRemovalToServerAdminAndNotToHouseholdWhenProfileOfDeclinedOfferIsDeleted() {
    var hostSocket = subscribedSocket(authTestSupport.accountBearer(host));
    var ownerSocket = subscribedSocket(authTestSupport.accountBearer(owner));
    var serverAdmin = adminIdentity();
    var serverAdminSocket = subscribedSocket(authTestSupport.accountBearer(serverAdmin));
    // Only a Profile with no active or pending share may be deleted.
    var orphan = unsharedManagedProfile();
    var shareId = offer(orphan);
    // Each change is read as it is delivered, so every side hears each step before the next one.
    var sides = List.of(hostSocket, ownerSocket, serverAdminSocket);
    sides.forEach(socket -> nextShare(socket, shareId));
    decide("rejectProfileShare", shareId);
    var declinedForHost = nextShare(hostSocket, shareId);
    nextShare(ownerSocket, shareId);
    var declinedForServerAdmin = nextShare(serverAdminSocket, shareId);
    assertThat(List.of(declinedForHost, declinedForServerAdmin))
        .extracting(share -> share.path("status").asString())
        .containsOnly("REJECTED");

    var deleted =
        http.execute(
            authTestSupport.freshAccountBearer(owner),
            """
            mutation { deleteProfile(input: {profileId: "%s"}) {
              deletedProfileId userErrors { __typename } } }
            """
                .formatted(orphan.getId()));
    assertThat(deleted.at("/data/deleteProfile/deletedProfileId").asString())
        .as("deletion in %s", deleted)
        .isEqualTo(orphan.getId().toString());
    var removal = nextRemoval(serverAdminSocket, shareId);
    var laterId = offer(managedProfile());

    // The decline was the Household's last change of the offer: it hears no removal after it.
    assertPartiesHidden(declinedForHost);
    assertThat(changesUntil(hostSocket, laterId))
        .extracting(change -> change.path("shareId").asString())
        .isNotEmpty()
        .doesNotContain(shareId);
    assertThat(declinedForServerAdmin.path("profile").path("name").asString())
        .isEqualTo(orphan.getName());
    assertThat(declinedForServerAdmin.path("offeredByDisplayName").asString())
        .isEqualTo(owner.account().getDisplayName());
    assertThat(removal.path("shareId").asString()).isEqualTo(shareId);
    assertThat(changesUntil(ownerSocket, laterId))
        .extracting(
            change -> change.path("shareId").asString(), change -> change.path("share").isNull())
        .isNotEmpty()
        .doesNotContain(tuple(shareId, true));
  }

  @Test
  @DisplayName(
      "Should deliver the withdrawn offer to its Household and nothing after it when the Profile of"
          + " a pending offer is deleted")
  void
      shouldDeliverWithdrawnOfferToItsHouseholdAndNothingAfterWhenProfileOfPendingOfferIsDeleted() {
    var hostSocket = subscribedSocket(authTestSupport.accountBearer(host));
    var orphan = managedProfile();
    var shareId = offer(orphan);
    nextShare(hostSocket, shareId);
    var serverAdmin = adminIdentity();

    var deleted =
        http.execute(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { administrativelyDeleteProfile(input: {profileId: "%s", reason: "closing"}) {
              profileId userErrors { __typename } } }
            """
                .formatted(orphan.getId()));
    assertThat(deleted.at("/data/administrativelyDeleteProfile/profileId").asString())
        .as("deletion in %s", deleted)
        .isEqualTo(orphan.getId().toString());
    var withdrawn = changesUntil(hostSocket, shareId).getLast();
    var laterId = offer(managedProfile());

    // The deletion withdrew the offer first; the Household hears that, and not the deletion.
    assertThat(withdrawn.path("share").path("status").asString())
        .as("change in %s", withdrawn)
        .isEqualTo("INVALIDATED");
    assertPartiesHidden(withdrawn.path("share"));
    assertThat(changesUntil(hostSocket, laterId))
        .extracting(change -> change.path("shareId").asString())
        .isNotEmpty()
        .doesNotContain(shareId);
  }

  @Test
  @DisplayName(
      "Should deliver the expiry and not the deletion to the Household when the Profile of an offer"
          + " whose time ran out is deleted before the sweep")
  void shouldDeliverExpiryAndNotDeletionToHouseholdWhenProfileOfLapsedOfferIsDeletedBeforeSweep() {
    var hostSocket = subscribedSocket(authTestSupport.accountBearer(host));
    // An offer whose time ran out is no longer pending, so its Profile may be deleted, though the
    // offer stays stored pending until the sweep reaches it.
    var orphan = unsharedManagedProfile();
    var shareId = offer(orphan);
    nextShare(hostSocket, shareId);
    var lapsed = shareRepository.findById(UUID.fromString(shareId)).orElseThrow();
    lapsed.setExpiresAt(Instant.now().minusSeconds(1));
    shareRepository.saveAndFlush(lapsed);

    var deleted =
        http.execute(
            authTestSupport.freshAccountBearer(owner),
            """
            mutation { deleteProfile(input: {profileId: "%s"}) {
              deletedProfileId userErrors { __typename } } }
            """
                .formatted(orphan.getId()));
    assertThat(deleted.at("/data/deleteProfile/deletedProfileId").asString())
        .as("deletion in %s", deleted)
        .isEqualTo(orphan.getId().toString());
    var ending = changesUntil(hostSocket, shareId).getLast();
    var laterId = offer(managedProfile());

    // The offer was over before its Profile went: the Household hears that it expired, and not
    // that the Profile was deleted.
    assertThat(ending.path("share").path("status").asString())
        .as("change in %s", ending)
        .isEqualTo("EXPIRED");
    assertPartiesHidden(ending.path("share"));
    assertThat(changesUntil(hostSocket, laterId))
        .extracting(change -> change.path("shareId").asString())
        .isNotEmpty()
        .doesNotContain(shareId);
  }

  @Test
  @DisplayName(
      "Should deliver the share's removal to the Profile's managers when its Household is deleted")
  void shouldDeliverTheSharesRemovalToTheProfilesManagersWhenItsHouseholdIsDeleted() {
    var ownerSocket = subscribedSocket(authTestSupport.accountBearer(owner));
    var stranger = identity();
    var strangerSocket = subscribedSocket(authTestSupport.accountBearer(stranger));
    var empty =
        transactionTemplate.execute(
            _ ->
                householdRepository.saveAndFlush(
                    HouseholdFixture.defaultHouseholdBuilder().build()));
    households.add(empty.getId());
    var shareId = offer(managedProfile(), empty.getId());
    nextShare(ownerSocket, shareId);
    var serverAdmin = adminIdentity();

    var deleted =
        http.execute(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { deleteEmptyHousehold(input: {householdId: "%s", reason: "closing"}) {
              deletedHouseholdId userErrors { __typename } } }
            """
                .formatted(empty.getId()));
    assertThat(deleted.at("/data/deleteEmptyHousehold/deletedHouseholdId").asString())
        .as("deletion in %s", deleted)
        .isEqualTo(empty.getId().toString());
    var removal = nextRemoval(ownerSocket, shareId);
    var visibleId = offer(managedProfile(), stranger);

    assertThat(removal.path("shareId").asString()).isEqualTo(shareId);
    assertThat(changesUntil(strangerSocket, visibleId))
        .extracting(change -> change.path("shareId").asString())
        .isNotEmpty()
        .doesNotContain(shareId);
  }

  @Test
  @DisplayName("Should not deliver a share when the subscriber may not read it")
  void shouldNotDeliverAShareWhenTheSubscriberMayNotReadIt() {
    var stranger = identity();
    var strangerSocket = subscribedSocket(authTestSupport.accountBearer(stranger));
    var orphan = managedProfile();

    var hiddenId = offer(orphan);
    var visibleId = offer(orphan, stranger);

    var received = changesUntil(strangerSocket, visibleId);
    assertThat(received)
        .extracting(change -> change.path("shareId").asString())
        .isNotEmpty()
        .doesNotContain(hiddenId);
  }

  @Test
  @DisplayName("Should stop delivering a Profile's shares when the subscriber stops managing it")
  void shouldStopDeliveringAProfilesSharesWhenTheSubscriberStopsManagingIt() {
    var manager = authTestSupport.createHouseholdMemberIdentity(owner);
    identities.add(manager);
    var managerSocket = subscribedSocket(authTestSupport.accountBearer(manager));
    var leaving = managedProfile();
    var kept = managedProfile();
    grantManagement(manager, leaving);
    grantManagement(manager, kept);
    var leavingShareId = offer(leaving);
    var keptShareId = offer(kept);
    nextShare(managerSocket, leavingShareId);
    nextShare(managerSocket, keptShareId);

    revokeManagement(manager, leaving);
    decide("rejectProfileShare", leavingShareId);
    decide("rejectProfileShare", keptShareId);

    var received = changesUntil(managerSocket, keptShareId);
    assertThat(received)
        .extracting(change -> change.path("shareId").asString())
        .isNotEmpty()
        .doesNotContain(leavingShareId);
    assertThat(received.getLast().path("share").path("status").asString()).isEqualTo("REJECTED");
  }

  @Test
  @DisplayName(
      "Should not deliver where else a Profile visits when a HouseholdAdmin only hosts the Profile")
  void shouldNotDeliverWhereElseAProfileVisitsWhenAHouseholdAdminOnlyHostsTheProfile() {
    var orphan = managedProfile();
    var hostedId = offer(orphan);
    decide("acceptProfileShare", hostedId);
    var hostSocket = subscribedSocket(authTestSupport.accountBearer(host));

    var elsewhereId = offer(orphan, identity());
    var visibleId = offer(managedProfile());

    var received = changesUntil(hostSocket, visibleId);
    assertThat(received)
        .extracting(change -> change.path("shareId").asString())
        .isNotEmpty()
        .doesNotContain(elsewhereId);
  }

  @Test
  @DisplayName("Should refuse profileShareChanged with FORBIDDEN when the session is device-bound")
  void shouldRefuseProfileShareChangedWithForbiddenWhenTheSessionIsDeviceBound() {
    var socket = connect(authTestSupport.deviceBoundProfileBearer(host));

    socket.subscribe(OPERATION, SUBSCRIPTION);

    var refusal = socket.nextMessageFor(OPERATION);
    assertThat(refusal.path("type").asString()).isEqualTo("next");
    assertThat(firstErrorCode(refusal.path("payload"))).isEqualTo("FORBIDDEN");
    assertThat(socket.nextMessageFor(OPERATION).path("type").asString()).isEqualTo("complete");
    assertThat(activeSubscriptions.count(FIELD)).isZero();
  }

  @Test
  @DisplayName(
      "Should end profileShareChanged with FORBIDDEN at the next change when the Account is"
          + " disabled after subscribing")
  void shouldEndProfileShareChangedWithForbiddenWhenTheAccountIsDisabledAfterSubscribing() {
    var socket = subscribedSocket(authTestSupport.accountBearer(host));
    var account = userAccountRepository.findById(host.account().getId()).orElseThrow();
    account.setEnabled(false);
    userAccountRepository.saveAndFlush(account);

    offer(managedProfile(), identity());

    var ending = socket.nextMessageFor(OPERATION);
    assertThat(ending.path("type").asString()).isEqualTo("error");
    assertThat(ending.path("payload").path(0).path("extensions").path("code").asString())
        .isEqualTo("FORBIDDEN");
    activeSubscriptions.awaitCount(FIELD, 0);
  }

  @Test
  @DisplayName("Should release profileShareChanged when the client completes the operation")
  void shouldReleaseProfileShareChangedWhenTheClientCompletesTheOperation() {
    var socket = subscribedSocket(authTestSupport.accountBearer(host));

    socket.complete(OPERATION);

    activeSubscriptions.awaitCount(FIELD, 0);
    assertThat(activeSubscriptions.count(FIELD)).isZero();
  }

  @Test
  @DisplayName("Should deliver nothing when an acceptance rolls back")
  void shouldDeliverNothingWhenAnAcceptanceRollsBack() {
    var hostSocket = subscribedSocket(authTestSupport.accountBearer(host));
    var twin =
        managedProfile(ProfileFixture.defaultProfileBuilder().name(host.profile().getName()));
    var shareId = offer(twin);
    nextShare(hostSocket, shareId);

    var conflict =
        http.execute(
            authTestSupport.accountBearer(host),
            """
            mutation { acceptProfileShare(input: {shareId: "%s"}) { userErrors { __typename } } }
            """
                .formatted(shareId));
    decide("rejectProfileShare", shareId);

    assertThat(conflict.at("/data/acceptProfileShare/userErrors/0/__typename").asString())
        .isEqualTo("ShareNameConflictError");
    assertThat(nextShare(hostSocket, shareId).path("status").asString()).isEqualTo("REJECTED");
  }

  private String offer(Profile profile) {
    return offer(profile, host);
  }

  private String offer(Profile profile, AuthTestSupport.TestIdentity target) {
    return offer(profile, target.household().getId());
  }

  private String offer(Profile profile, UUID householdId) {
    var response =
        http.execute(
            authTestSupport.accountBearer(owner),
            """
            mutation { offerProfileShare(input: {profileId: "%s", householdId: "%s"}) {
              share { id status } userErrors { __typename } } }
            """
                .formatted(profile.getId(), householdId));
    var share = response.at("/data/offerProfileShare/share");
    assertThat(share.path("status").asString()).as("offer in %s", response).isEqualTo("PENDING");
    return share.path("id").asString();
  }

  // The host decides, or ends, a share into its own Household.
  private void decide(String mutation, String shareId) {
    var response =
        http.execute(
            authTestSupport.accountBearer(host),
            """
            mutation { %s(input: {shareId: "%s"}) { share { status } userErrors { __typename } } }
            """
                .formatted(mutation, shareId));
    assertThat(response.at("/data/%s/userErrors".formatted(mutation)).isEmpty())
        .as("%s in %s", mutation, response)
        .isTrue();
  }

  private void cancel(String shareId) {
    var response =
        http.execute(
            authTestSupport.accountBearer(owner),
            """
            mutation { cancelProfileShare(input: {shareId: "%s"}) { userErrors { __typename } } }
            """
                .formatted(shareId));
    assertThat(response.at("/data/cancelProfileShare/userErrors").isEmpty())
        .as("cancel in %s", response)
        .isTrue();
  }

  /** A Profile the HouseholdAdmin creates in their Household; answers its share there. */
  private String createProfileAtHome(AuthTestSupport.TestIdentity admin) {
    var response =
        http.execute(
            authTestSupport.accountBearer(admin),
            """
            mutation { createProfile(input: {householdId: "%s", name: "Pebble", kind: ADULT}) {
              profile { id } userErrors { __typename } } }
            """
                .formatted(admin.household().getId()));
    var profileId = response.at("/data/createProfile/profile/id").asString();
    assertThat(profileId).as("creation in %s", response).isNotEmpty();
    return shareRepository
        .findByProfileIdAndHouseholdIdAndStatus(
            UUID.fromString(profileId), admin.household().getId(), ProfileShareStatus.ACTIVE)
        .orElseThrow()
        .getId()
        .toString();
  }

  private Profile managedProfile() {
    return managedProfile(ProfileFixture.defaultProfileBuilder());
  }

  /** A Profile the owner manages, available nowhere. */
  private Profile unsharedManagedProfile() {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(owner.household().getId())
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(owner.account().getId())
                  .profileId(profile.getId())
                  .build());
          return profile;
        });
  }

  /** A Profile the owner manages, available in the owner's Household. */
  private Profile managedProfile(Profile.ProfileBuilder<?, ?> builder) {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  builder.householdId(owner.household().getId()).build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(owner.account().getId())
                  .profileId(profile.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(owner.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return profile;
        });
  }

  private void grantManagement(AuthTestSupport.TestIdentity manager, Profile profile) {
    profileManagerRepository.saveAndFlush(
        ProfileManager.builder()
            .accountId(manager.account().getId())
            .profileId(profile.getId())
            .build());
  }

  private void revokeManagement(AuthTestSupport.TestIdentity manager, Profile profile) {
    transactionTemplate.executeWithoutResult(
        _ ->
            profileManagerRepository.findByProfileId(profile.getId()).stream()
                .filter(grant -> grant.getAccountId().equals(manager.account().getId()))
                .forEach(profileManagerRepository::delete));
  }

  private AuthTestSupport.TestIdentity identity() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);
    return identity;
  }

  private AuthTestSupport.TestIdentity adminIdentity() {
    var identity = authTestSupport.createAdminIdentity();
    identities.add(identity);
    return identity;
  }

  private GraphQlTransportWsTestClient subscribedSocket(String bearer) {
    var before = activeSubscriptions.count(FIELD);
    var socket = connect(bearer);
    socket.subscribe(OPERATION, SUBSCRIPTION);
    activeSubscriptions.awaitCount(FIELD, (int) before + 1);
    return socket;
  }

  private GraphQlTransportWsTestClient connect(String bearer) {
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, bearer);
    sockets.add(socket);
    return socket;
  }

  // What the target Household's HouseholdAdmins still get once the offer or the share is over: the
  // share's own fields, and none of the Profile's details, the offerer's name, or why the system
  // withdrew the offer (ADR 0024).
  private static void assertPartiesHidden(JsonNode share) {
    assertThat(share.path("id").asString()).as("id in %s", share).isNotEmpty();
    assertThat(share.path("profile").isNull()).as("profile in %s", share).isTrue();
    assertThat(share.path("visitor").isNull()).as("visitor in %s", share).isTrue();
    assertThat(share.path("offeredByDisplayName").isNull()).as("offerer in %s", share).isTrue();
    assertThat(share.path("household").isNull()).as("household in %s", share).isTrue();
    assertThat(share.path("invalidationReason").isNull()).as("reason in %s", share).isTrue();
  }

  private static JsonNode nextChange(GraphQlTransportWsTestClient socket) {
    var message = socket.nextMessageFor(OPERATION);
    assertThat(message.path("type").asString()).as("message %s", message).isEqualTo("next");
    var payload = message.path("payload");
    assertThat(payload.path("errors").isMissingNode()).as("errors in %s", payload).isTrue();
    return payload.path("data").path(FIELD);
  }

  // The next change of one share, skipping changes of shares that this test set up.
  private static JsonNode nextShare(GraphQlTransportWsTestClient socket, String shareId) {
    return changesUntil(socket, shareId).getLast().path("share");
  }

  private static JsonNode nextShareWithStatus(
      GraphQlTransportWsTestClient socket, String shareId, String status) {
    while (true) {
      var share = nextShare(socket, shareId);
      if (status.equals(share.path("status").asString())) {
        return share;
      }
    }
  }

  // A change carries the share as it reads on delivery, so a change made before the deletion may
  // still be on its way; the removal is the first change without the share.
  private static JsonNode nextRemoval(GraphQlTransportWsTestClient socket, String shareId) {
    while (true) {
      var change = changesUntil(socket, shareId).getLast();
      if (change.path("share").isNull()) {
        return change;
      }
    }
  }

  // Every change up to and including the next change of the share, in delivery order.
  private static List<JsonNode> changesUntil(GraphQlTransportWsTestClient socket, String shareId) {
    var changes = new ArrayList<JsonNode>();
    while (true) {
      var change = nextChange(socket);
      changes.add(change);
      if (shareId.equals(change.path("shareId").asString())) {
        return changes;
      }
    }
  }
}
