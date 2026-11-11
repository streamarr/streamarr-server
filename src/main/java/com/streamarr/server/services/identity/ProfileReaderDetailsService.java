package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.ProfileAuthority;
import com.streamarr.server.domain.auth.ProfileReaderRelation;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What each Profile of a response is to the Account that reads it: whether the Account manages it
 * or supervises it, and the Household it belongs to. The answer for a whole page takes a fixed
 * number of statements, so the reader's relationships are read in SQL that mirrors the Cedar facts,
 * not decided for each Profile.
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

  /** What each of the Profiles that exist is to the reader, keyed by its ID. */
  @Transactional(readOnly = true)
  public Map<UUID, ProfileReaderDetails> details(
      AuthenticatedIdentity reader, Set<UUID> profileIds) {
    var relations = profileRepository.relationsTo(reader.accountId(), profileIds);
    var inspectsEveryHousehold =
        !relations.isEmpty() && authorizationService.isAllowed(reader, new Intent.ViewHouseholds());
    var shown =
        relations.stream()
            .filter(relation -> inspectsEveryHousehold || knowsTheHousehold(relation))
            .map(ProfileReaderRelation::householdId)
            .collect(Collectors.toSet());
    var households =
        householdRepository.findAllById(shown).stream()
            .collect(Collectors.toMap(Household::getId, Function.identity()));

    var details = new HashMap<UUID, ProfileReaderDetails>();
    relations.forEach(
        relation ->
            details.put(
                relation.profileId(),
                new ProfileReaderDetails(
                    relation.authority(),
                    shown.contains(relation.householdId())
                        ? Optional.ofNullable(households.get(relation.householdId()))
                        : Optional.empty())));
    return details;
  }

  private static boolean knowsTheHousehold(ProfileReaderRelation relation) {
    return relation.belongsToReadersHousehold()
        || relation.authority().filter(ProfileAuthority.MANAGES::equals).isPresent();
  }
}
