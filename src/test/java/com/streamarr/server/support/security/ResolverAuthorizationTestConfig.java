package com.streamarr.server.support.security;

import static org.mockito.Mockito.mock;

import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class ResolverAuthorizationTestConfig {

  @Bean
  ProfileHouseholdShareRepository profileHouseholdShareRepository() {
    return mock(ProfileHouseholdShareRepository.class);
  }
}
