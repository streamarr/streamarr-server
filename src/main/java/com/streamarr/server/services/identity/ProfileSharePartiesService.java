package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Names the parties of the shares one reader sees: the Profile, the Account that offered it, and
 * the Household it is into. Each party is shown only to readers who may already know it, and a
 * hidden party is absent from the answer.
 *
 * <p>The target Household's HouseholdAdmins see the Profile and the offerer only while the offer is
 * pending or the share is active, as the share stands when its parties are read. Once it is over
 * they see neither; only ServerAdmins, who may read the Household's share history, still do. The
 * offering side sees its own shares at every status. The Household's name is for the Profile's side
 * and ServerAdmins.
 *
 * <p>Each side is decided for the whole batch at once, so a page needs the same number of reads
 * whatever it holds. When a share can be shown from either side, the side with fewer distinct
 * values in the batch is asked first, and the other side only about the shares the first did not
 * show.
 */
@Service
@RequiredArgsConstructor
public class ProfileSharePartiesService {

  private final AuthorizationService authorizationService;
  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileRepository profileRepository;
  private final UserAccountRepository userAccountRepository;
  private final HouseholdRepository householdRepository;
  private final Clock clock;

  /**
   * The Profile on each share, and whether it is a visitor in the share's Household, for the
   * offering side at every status: the Profile's managers and ServerAdmins. The target Household's
   * HouseholdAdmins see it only while the offer is pending or the share is active.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<ShareReference, SharedProfile> profiles(
      AuthenticatedIdentity reader, Set<ShareReference> shares) {
    var targetSide = targetSide(shares);
    var existing = profilesById(shares);
    var authority = new BatchAuthority(reader, shares, existing.keySet());
    var shown =
        authority.shownByEitherSide(
            shares.stream().filter(share -> existing.containsKey(share.profileId())).toList(),
            targetSide);
    var personal = personalProfileIds(shown);

    var profiles = new HashMap<ShareReference, SharedProfile>();
    shown.forEach(
        share -> {
          var profile = existing.get(share.profileId());
          profiles.put(
              share,
              SharedProfile.builder()
                  .profile(profile)
                  .personalProfile(personal.contains(share.profileId()))
                  .visitor(!share.householdId().equals(profile.getHouseholdId()))
                  .build());
        });
    return profiles;
  }

  /**
   * The display name of the Account that offered each share, for the target Household's
   * HouseholdAdmins while the offer is pending or the share is active, and for ServerAdmins at
   * every status. A share the system made, or whose offering Account is deleted, names nobody.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<ShareReference, String> offererNames(
      AuthenticatedIdentity reader, Set<ShareReference> shares) {
    var targetSide = targetSide(shares);
    // The offerer's name is decided on the target side alone, so no Profile is consulted.
    var authority = new BatchAuthority(reader, shares, Set.of());
    var shown =
        authority.shownBy(
            shares.stream().filter(share -> share.offeredBy().isPresent()).toList(), targetSide);
    var offererIds =
        shown.stream().flatMap(share -> share.offeredBy().stream()).collect(Collectors.toSet());
    var names =
        userAccountRepository.findAllById(offererIds).stream()
            .collect(Collectors.toMap(UserAccount::getId, UserAccount::getDisplayName));

    var offerers = new HashMap<ShareReference, String>();
    shown.forEach(
        share -> share.offeredBy().map(names::get).ifPresent(name -> offerers.put(share, name)));
    return offerers;
  }

  /**
   * The Household each share is into, for the offering side: the Profile's managers and
   * ServerAdmins. The Account of a supervised Personal Profile is not one of its managers, and
   * administering the Household is not enough.
   */
  @Transactional(readOnly = true)
  public Map<ShareReference, Household> households(
      AuthenticatedIdentity reader, Set<ShareReference> shares) {
    var authority = new BatchAuthority(reader, shares, profilesById(shares).keySet());
    var shown = authority.shownByEitherSide(shares, ProfileSharePartiesService::historySide);
    var loaded =
        householdRepository.findAllById(distinct(shown, ShareReference::householdId)).stream()
            .collect(Collectors.toMap(Household::getId, Function.identity()));

    var households = new HashMap<ShareReference, Household>();
    shown.stream()
        .filter(share -> loaded.containsKey(share.householdId()))
        .forEach(share -> households.put(share, loaded.get(share.householdId())));
    return households;
  }

  // What the target Household's side asks to see each share's Profile and offerer: its
  // HouseholdAdmins while the share is still live, otherwise only a reader of its share history.
  // A page reads its shares before their parties load, and a share can end in between, so the
  // status is read again here, in the same snapshot as the parties that the caller then reads.
  private Function<ShareReference, Optional<Intent.UnitIntent>> targetSide(
      Set<ShareReference> shares) {
    var now = clock.instant();
    var live =
        shareRepository.findAllById(distinct(shares, ShareReference::shareId)).stream()
            .filter(share -> share.statusAt(now).isLive())
            .map(ProfileHouseholdShare::getId)
            .collect(Collectors.toSet());
    return share ->
        live.contains(share.shareId())
            ? Optional.of(new Intent.ViewHouseholdAdministration(share.householdId()))
            : historySide(share);
  }

  private static Optional<Intent.UnitIntent> historySide(ShareReference share) {
    return Optional.of(new Intent.ViewHouseholdShareHistory(share.householdId()));
  }

  private Map<UUID, Profile> profilesById(Set<ShareReference> shares) {
    return profileRepository.findAllById(distinct(shares, ShareReference::profileId)).stream()
        .collect(Collectors.toMap(Profile::getId, Function.identity()));
  }

  private Set<UUID> personalProfileIds(Collection<ShareReference> shares) {
    var profileIds = distinct(shares, ShareReference::profileId);
    if (profileIds.isEmpty()) {
      return Set.of();
    }

    return userAccountRepository.findByPersonalProfileIdIn(profileIds).stream()
        .map(UserAccount::getPersonalProfileId)
        .collect(Collectors.toSet());
  }

  private static Set<UUID> distinct(
      Collection<ShareReference> shares, Function<ShareReference, UUID> id) {
    return shares.stream().map(id).collect(Collectors.toSet());
  }

  /**
   * One reader's answers over one batch of shares, each side asked about the whole batch at once.
   * The Profile side is asked only about Profiles that exist, since no decision can be made about a
   * missing one.
   */
  private final class BatchAuthority {

    private final AuthenticatedIdentity reader;
    private final Set<UUID> existingProfiles;
    private final boolean householdSideFirst;

    private BatchAuthority(
        AuthenticatedIdentity reader, Set<ShareReference> shares, Set<UUID> existingProfiles) {
      this.reader = reader;
      this.existingProfiles = existingProfiles;
      this.householdSideFirst =
          distinct(shares, ShareReference::householdId).size()
              <= distinct(shares, ShareReference::profileId).size();
    }

    /** The shares the given side of the target Household shows, or the Profile's side does. */
    Set<ShareReference> shownByEitherSide(
        Collection<ShareReference> shares,
        Function<ShareReference, Optional<Intent.UnitIntent>> householdSide) {
      Function<ShareReference, Optional<Intent.UnitIntent>> first =
          householdSideFirst ? householdSide : this::profileSide;
      Function<ShareReference, Optional<Intent.UnitIntent>> second =
          householdSideFirst ? this::profileSide : householdSide;
      var shown = new HashSet<>(shownBy(shares, first));
      var rest = shares.stream().filter(share -> !shown.contains(share)).toList();
      shown.addAll(shownBy(rest, second));
      return shown;
    }

    /** The shares the side allows, decided for all of them at once. */
    Set<ShareReference> shownBy(
        Collection<ShareReference> shares,
        Function<ShareReference, Optional<Intent.UnitIntent>> side) {
      var asked =
          shares.stream().flatMap(share -> side.apply(share).stream()).collect(Collectors.toSet());
      var allowed = authorizationService.allowedAmong(reader, asked);
      return shares.stream()
          .filter(share -> side.apply(share).filter(allowed::contains).isPresent())
          .collect(Collectors.toSet());
    }

    private Optional<Intent.UnitIntent> profileSide(ShareReference share) {
      return Optional.of(share.profileId())
          .filter(existingProfiles::contains)
          .map(Intent.ViewProfileShareHouseholds::new);
    }
  }
}
