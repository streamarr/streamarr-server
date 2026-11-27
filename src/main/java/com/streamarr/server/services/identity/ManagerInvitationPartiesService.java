package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
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
 * The names that manager invitations show, read through the parties they name. The caller has
 * already read each invitation, so nothing here decides who may see it.
 */
@Service
@RequiredArgsConstructor
public class ManagerInvitationPartiesService {

  private final ProfileRepository profileRepository;
  private final UserAccountRepository userAccountRepository;

  /**
   * Each invitation's names: the Profile's and the inviter's current names, or the names it stored
   * once a party is deleted. Two reads, however many invitations there are.
   */
  @Transactional(readOnly = true)
  public Map<ManagerInvitationParties, ManagerInvitationNames> currentNames(
      Set<ManagerInvitationParties> invitations) {
    var profileNames =
        profileRepository
            .findAllById(idsOf(invitations, ManagerInvitationParties::profileId))
            .stream()
            .collect(Collectors.toMap(Profile::getId, Profile::getName));
    var inviterNames =
        userAccountRepository
            .findAllById(idsOf(invitations, ManagerInvitationParties::inviterAccountId))
            .stream()
            .collect(Collectors.toMap(UserAccount::getId, UserAccount::getDisplayName));
    return invitations.stream()
        .collect(
            Collectors.toMap(
                Function.identity(),
                invitation -> invitation.namesAmong(profileNames, inviterNames)));
  }

  private static Set<UUID> idsOf(
      Set<ManagerInvitationParties> invitations,
      Function<ManagerInvitationParties, Optional<UUID>> party) {
    return invitations.stream().map(party).flatMap(Optional::stream).collect(Collectors.toSet());
  }
}
