package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileReaderRelation;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What each Profile of a response is to the Account that reads it: whether the Account manages it
 * or supervises it. The answer for a whole page takes a fixed number of statements, so the reader's
 * relationships are read in SQL that mirrors the Cedar facts, not decided for each Profile.
 */
@Service
@RequiredArgsConstructor
public class ProfileReaderDetailsService {

  private final ProfileRepository profileRepository;

  /** What each of the Profiles that exist is to the reader, keyed by its ID. */
  @Transactional(readOnly = true)
  public Map<UUID, ProfileReaderDetails> details(
      AuthenticatedIdentity reader, Set<UUID> profileIds) {
    return profileRepository.relationsTo(reader.accountId(), profileIds).stream()
        .collect(
            Collectors.toMap(
                ProfileReaderRelation::profileId,
                relation -> new ProfileReaderDetails(relation.authority())));
  }
}
