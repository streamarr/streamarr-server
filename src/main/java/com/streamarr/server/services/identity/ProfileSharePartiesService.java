package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Names the parties of the shares one reader sees: the Profile, the Account that offered it, and
 * the Household it is into. Each party is shown only to readers who may already know it, and a
 * hidden party is absent from the answer.
 *
 * <p>Each authority is decided once per batch. When a share can be shown from either side, the side
 * with fewer distinct values in the batch is asked first, so a page of the shares into one
 * Household, or of one Profile, needs only a fixed number of decisions for its Profiles and
 * Households.
 */
@Service
@RequiredArgsConstructor
public class ProfileSharePartiesService {

  private final AuthorizationService authorizationService;
  private final ProfileRepository profileRepository;
  private final UserAccountRepository userAccountRepository;
  private final HouseholdRepository householdRepository;

  /**
   * The Profile on each share, for the target Household's HouseholdAdmins and for readers who may
   * see the Profile's administration: its managers, a HouseholdAdmin where it is available, and
   * ServerAdmins.
   */
  @Transactional(readOnly = true)
  public Map<ShareReference, SharedProfile> profiles(
      AuthenticatedIdentity reader, Set<ShareReference> shares) {
    var existing = profilesById(shares);
    var authority = new BatchAuthority(reader, shares, existing.keySet());
    var shown =
        shares.stream()
            .filter(share -> existing.containsKey(share.profileId()))
            .filter(share -> authority.eitherSide(share, Intent.ViewProfileAdministration::new))
            .toList();
    var personal = personalProfileIds(shown);

    var profiles = new HashMap<ShareReference, SharedProfile>();
    shown.forEach(
        share ->
            profiles.put(
                share,
                new SharedProfile(
                    existing.get(share.profileId()), personal.contains(share.profileId()))));
    return profiles;
  }

  /**
   * The display name of the Account that offered each share, for the target Household's
   * HouseholdAdmins. A share the system made, or whose offering Account is deleted, names nobody.
   */
  @Transactional(readOnly = true)
  public Map<ShareReference, String> offererNames(
      AuthenticatedIdentity reader, Set<ShareReference> shares) {
    // The offerer's name is decided on the target side alone, so no Profile is consulted.
    var authority = new BatchAuthority(reader, shares, Set.of());
    var shown =
        shares.stream()
            .filter(share -> share.offeredBy().isPresent())
            .filter(authority::administersTarget)
            .toList();
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
   * The Household each share is into, for that Household's HouseholdAdmins and for the offering
   * side: the Profile's managers, the Account whose Personal Profile it is, and ServerAdmins.
   */
  @Transactional(readOnly = true)
  public Map<ShareReference, Household> households(
      AuthenticatedIdentity reader, Set<ShareReference> shares) {
    var authority = new BatchAuthority(reader, shares, profilesById(shares).keySet());
    var shown =
        shares.stream()
            .filter(share -> authority.eitherSide(share, Intent.ViewProfileShareHouseholds::new))
            .toList();
    var loaded =
        householdRepository.findAllById(distinct(shown, ShareReference::householdId)).stream()
            .collect(Collectors.toMap(Household::getId, Function.identity()));

    var households = new HashMap<ShareReference, Household>();
    shown.stream()
        .filter(share -> loaded.containsKey(share.householdId()))
        .forEach(share -> households.put(share, loaded.get(share.householdId())));
    return households;
  }

  private Map<UUID, Profile> profilesById(Set<ShareReference> shares) {
    return profileRepository.findAllById(distinct(shares, ShareReference::profileId)).stream()
        .collect(Collectors.toMap(Profile::getId, Function.identity()));
  }

  private Set<UUID> personalProfileIds(List<ShareReference> shares) {
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
   * One reader's answers over one batch of shares, each question asked once. The Profile side is
   * asked only about Profiles that exist, since no decision can be made about a missing one.
   */
  private final class BatchAuthority {

    private final AuthenticatedIdentity reader;
    private final Set<UUID> existingProfiles;
    private final boolean householdSideFirst;
    private final Map<Intent.UnitIntent, Boolean> answers = new HashMap<>();

    private BatchAuthority(
        AuthenticatedIdentity reader, Set<ShareReference> shares, Set<UUID> existingProfiles) {
      this.reader = reader;
      this.existingProfiles = existingProfiles;
      this.householdSideFirst =
          distinct(shares, ShareReference::householdId).size()
              <= distinct(shares, ShareReference::profileId).size();
    }

    boolean administersTarget(ShareReference share) {
      return allows(new Intent.ViewHouseholdAdministration(share.householdId()));
    }

    /** Whether the reader administers the target Household, or the Profile side allows it. */
    boolean eitherSide(ShareReference share, Function<UUID, Intent.UnitIntent> profileSide) {
      BooleanSupplier target = () -> administersTarget(share);
      BooleanSupplier profile =
          () ->
              existingProfiles.contains(share.profileId())
                  && allows(profileSide.apply(share.profileId()));
      return householdSideFirst
          ? target.getAsBoolean() || profile.getAsBoolean()
          : profile.getAsBoolean() || target.getAsBoolean();
    }

    private boolean allows(Intent.UnitIntent intent) {
      return answers.computeIfAbsent(
          intent, asked -> authorizationService.isAllowed(reader, asked));
    }
  }
}
