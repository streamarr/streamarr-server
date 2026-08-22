package com.streamarr.server.services.authorization.cedar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.streamarr.server.domain.auth.AccountAuthorityFacts;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.AuthorizationDecider;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The engine package is wired by component scan with package-private classes and one
 * multi-constructor bean; this proves Spring can build the decider without the full application.
 */
@Tag("UnitTest")
@SpringBootTest(
    classes = {
      CedarAuthorizationWiringTest.ProductionCedarModule.class,
      CedarAuthorizationWiringTest.Meters.class
    })
@DisplayName("Cedar Authorization Wiring Tests")
class CedarAuthorizationWiringTest {

  @Autowired private AuthorizationDecider decider;

  @MockitoBean private UserAccountRepository userAccountRepository;

  @Configuration(proxyBeanMethods = false)
  @ComponentScan(basePackageClasses = CedarEngineConfiguration.class)
  static class ProductionCedarModule {}

  @TestConfiguration(proxyBeanMethods = false)
  static class Meters {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  @Test
  @DisplayName("Should decide through the wired Cedar engine when the context starts")
  void shouldDecideThroughWiredCedarEngineWhenContextStarts() {
    when(userAccountRepository.findAuthorityFacts(any()))
        .thenReturn(Optional.of(new AccountAuthorityFacts(true, true)));
    var identity =
        AuthenticatedIdentityFixture.defaultIdentityBuilder()
            .role(AccountRole.USER)
            .scope(TokenScope.PROFILE)
            .streamSessionId(null)
            .build();

    assertThat(decider).isInstanceOf(CedarAuthorizationDecider.class);
    assertThat(decider.decide(identity, new Intent.AddLibrary()))
        .isEqualTo(new Decision.Allowed<>(AuthorizationUnit.INSTANCE));
  }
}
