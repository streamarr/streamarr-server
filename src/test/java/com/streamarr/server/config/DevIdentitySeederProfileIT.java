package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * The other half of the known-credential safeguard: PackagedConfigurationTest proves the packaged
 * artifact never self-activates the dev profile; this pins that without it, the seeder bean — and
 * its default admin credential — does not exist at all.
 */
@Tag("IntegrationTest")
@DisplayName("Dev Identity Seeder Profile Integration Tests")
class DevIdentitySeederProfileIT extends AbstractIntegrationTest {

  @Autowired private ApplicationContext applicationContext;

  @Test
  @DisplayName("Should not register dev identity seeder when dev profile inactive")
  void shouldNotRegisterDevIdentitySeederWhenDevProfileInactive() {
    assertThat(applicationContext.getBeanProvider(DevIdentitySeeder.class).getIfAvailable())
        .isNull();
  }
}
