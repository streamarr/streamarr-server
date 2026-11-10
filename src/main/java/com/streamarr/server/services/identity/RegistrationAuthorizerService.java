package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Names the person who authorized each Device registration, for those who may list it. */
@Service
@RequiredArgsConstructor
public class RegistrationAuthorizerService {

  private final AuthorizationService authorizationService;
  private final UserAccountRepository userAccountRepository;

  /**
   * The display name of the Account that authorized each registration, for readers who may list
   * that Household's registrations. A hidden or deleted authorizer is absent from the answer.
   */
  @Transactional(readOnly = true)
  public Map<RegistrationAuthorizer, String> displayNames(
      AuthenticatedIdentity reader, Set<RegistrationAuthorizer> authorizers) {
    var listable =
        authorizers.stream()
            .map(RegistrationAuthorizer::householdId)
            .distinct()
            .filter(
                householdId ->
                    authorizationService.isAllowed(
                        reader, new Intent.ViewDeviceAdministration(householdId)))
            .collect(Collectors.toSet());
    var shown =
        authorizers.stream()
            .filter(authorizer -> listable.contains(authorizer.householdId()))
            .toList();
    var names =
        userAccountRepository
            .findAllById(
                shown.stream().map(RegistrationAuthorizer::accountId).collect(Collectors.toSet()))
            .stream()
            .collect(Collectors.toMap(UserAccount::getId, UserAccount::getDisplayName));

    var displayNames = new HashMap<RegistrationAuthorizer, String>();
    shown.stream()
        .filter(authorizer -> names.containsKey(authorizer.accountId()))
        .forEach(authorizer -> displayNames.put(authorizer, names.get(authorizer.accountId())));
    return displayNames;
  }
}
