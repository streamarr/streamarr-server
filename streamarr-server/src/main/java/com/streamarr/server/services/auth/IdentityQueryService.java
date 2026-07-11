package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model behind the GraphQL me query: account, memberships, and selectable profiles. */
@Service
@RequiredArgsConstructor
public class IdentityQueryService {

  private final UserAccountRepository userAccountRepository;
  private final HouseholdMembershipRepository membershipRepository;
  private final HouseholdRepository householdRepository;
  private final AccountProfileRepository accountProfileRepository;
  private final ProfileRepository profileRepository;

  @Transactional(readOnly = true)
  public MeView meView(AuthenticatedIdentity identity) {
    var account =
        userAccountRepository
            .findById(identity.accountId())
            .orElseThrow(AuthenticationRequiredException::new);

    var memberships =
        membershipRepository.findByAccountId(identity.accountId()).stream()
            .map(membership -> membershipView(identity, membership))
            .toList();

    return new MeView(account, identity.scope(), memberships);
  }

  private MembershipView membershipView(
      AuthenticatedIdentity identity, HouseholdMembership membership) {
    var household =
        householdRepository
            .findById(membership.getHouseholdId())
            .orElseThrow(AuthenticationRequiredException::new);

    var profiles =
        accountProfileRepository
            .findByAccountIdAndHouseholdId(identity.accountId(), membership.getHouseholdId())
            .stream()
            .map(link -> profileRepository.findById(link.getProfileId()))
            .flatMap(java.util.Optional::stream)
            .map(
                profile ->
                    new SelectableProfileView(
                        profile.getId(),
                        profile.getName(),
                        profile.getId().equals(identity.profileId())))
            .toList();

    return new MembershipView(
        household.getId(), household.getName(), membership.getHouseholdRole(), profiles);
  }

  public record MeView(UserAccount account, TokenScope scope, List<MembershipView> memberships) {}

  public record MembershipView(
      UUID householdId,
      String householdName,
      HouseholdRole householdRole,
      List<SelectableProfileView> profiles) {}

  public record SelectableProfileView(UUID id, String name, boolean active) {}
}
