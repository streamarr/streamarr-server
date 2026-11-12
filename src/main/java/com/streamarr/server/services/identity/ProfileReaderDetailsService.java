package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.ProfileAuthority;
import com.streamarr.server.domain.auth.ProfileReaderRelation;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.language.LanguagePreferenceRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What each Profile of a response is to the Account that reads it: whether the Account manages it
 * or supervises it, the Household it belongs to, and its language preferences. The answer for a
 * whole page takes a fixed number of statements, so the reader's relationships are read in SQL that
 * mirrors the Cedar facts, not decided for each Profile.
 *
 * <p>The Household is for the Profile's managers, ServerAdmins and the Accounts of that Household.
 * A HouseholdAdmin who reads a Profile that only visits their Household learns nothing of where it
 * comes from (ADR 0024 §Read authorization).
 */
@Service
@RequiredArgsConstructor
public class ProfileReaderDetailsService {

  private final AuthorizationService authorizationService;
  private final ProfileRepository profileRepository;
  private final HouseholdRepository householdRepository;
  private final UserAccountRepository userAccountRepository;
  private final LanguagePreferenceRepository languagePreferenceRepository;

  /**
   * The own language preferences of each of the Profiles the reader may read them for, keyed by its
   * ID, as viewProfileActivity decides (ADR 0024 §Read authorization): the Profiles it manages and
   * the one its session has selected, or every Profile for a ServerAdmin, while its Account is
   * enabled.
   */
  @Transactional(readOnly = true)
  public Map<UUID, ProfileLanguagePreferences> languagePreferences(
      AuthenticatedIdentity reader, Set<UUID> profileIds) {
    var relations = profileRepository.relationsTo(reader.accountId(), profileIds);
    var managedOrSelected =
        relations.stream()
            .filter(relation -> managesOrHasSelected(reader, relation))
            .map(ProfileReaderRelation::profileId)
            .collect(Collectors.toSet());
    if (managedOrSelected.size() < relations.size()
        && authorizationService.isAllowed(reader, new Intent.ViewHouseholds())) {
      return languagePreferenceRepository.findProfilePreferences(
          relations.stream().map(ProfileReaderRelation::profileId).toList());
    }

    if (managedOrSelected.isEmpty() || !isEnabled(reader)) {
      return Map.of();
    }

    return languagePreferenceRepository.findProfilePreferences(managedOrSelected);
  }

  private static boolean managesOrHasSelected(
      AuthenticatedIdentity reader, ProfileReaderRelation relation) {
    return relation.profileId().equals(reader.profileId())
        || relation.authority().filter(ProfileAuthority.MANAGES::equals).isPresent();
  }

  // Cedar forbids a disabled Account every action (server-administration.cedar), and
  // LivePrincipalAuthorityContributor reads this same fact for it.
  private boolean isEnabled(AuthenticatedIdentity reader) {
    return userAccountRepository
        .findAuthorityFacts(reader.accountId())
        .filter(AccountAuthorityFacts::enabled)
        .isPresent();
  }

  /** What each of the Profiles that exist is to the reader, keyed by its ID. */
  @Transactional(readOnly = true)
  public Map<UUID, ProfileReaderDetails> details(
      AuthenticatedIdentity reader, Set<UUID> profileIds) {
    var relations = profileRepository.relationsTo(reader.accountId(), profileIds);
    var inspectsEveryHousehold =
        !relations.isEmpty() && authorizationService.isAllowed(reader, new Intent.ViewHouseholds());
    Predicate<ProfileReaderRelation> showsTheHousehold =
        relation -> inspectsEveryHousehold || knowsTheHousehold(relation);
    var shown =
        relations.stream()
            .filter(showsTheHousehold)
            .map(ProfileReaderRelation::householdId)
            .collect(Collectors.toSet());
    var households =
        householdRepository.findAllById(shown).stream()
            .collect(Collectors.toMap(Household::getId, Function.identity()));

    // Decided for each Profile: one Profile shows its Household for no other Profile from there.
    var details = new HashMap<UUID, ProfileReaderDetails>();
    relations.forEach(
        relation ->
            details.put(
                relation.profileId(),
                new ProfileReaderDetails(
                    relation.authority(),
                    showsTheHousehold.test(relation)
                        ? Optional.ofNullable(households.get(relation.householdId()))
                        : Optional.empty())));
    return details;
  }

  private static boolean knowsTheHousehold(ProfileReaderRelation relation) {
    return relation.belongsToReadersHousehold()
        || relation.authority().filter(ProfileAuthority.MANAGES::equals).isPresent();
  }
}
