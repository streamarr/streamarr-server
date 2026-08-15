package com.streamarr.server.support.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.services.authorization.RequestAuthorizationStateResolver;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class ResolverAuthorizationTestConfig {

  @Bean
  RequestAuthorizationStateResolver requestAuthorizationStateResolver() {
    var resolver = mock(RequestAuthorizationStateResolver.class);
    authorizeProfileContext(resolver);
    return resolver;
  }

  public static void authorizeProfileContext(RequestAuthorizationStateResolver resolver) {
    when(resolver.resolve(any()))
        .thenAnswer(
            invocation -> {
              var token = invocation.<StreamarrAuthenticationToken>getArgument(0);
              var identity = token.getPrincipal();
              var account =
                  UserAccount.builder()
                      .email("resolver@example.com")
                      .displayName("Resolver User")
                      .passwordHash("not-used")
                      .accountRole(identity.role())
                      .homeHouseholdId(TestIdentityConstants.HOUSEHOLD_ID)
                      .householdRole(HouseholdRole.OWNER)
                      .build();
              account.setId(TestIdentityConstants.ACCOUNT_ID);
              return new RequestAuthorizationStateResolver.AuthorizationState(
                  account, identity.profileId());
            });
  }

  @Bean
  ProfileHouseholdShareRepository profileHouseholdShareRepository() {
    return mock(ProfileHouseholdShareRepository.class);
  }
}
