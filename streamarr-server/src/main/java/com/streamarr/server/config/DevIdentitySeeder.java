package com.streamarr.server.config;

import com.streamarr.server.services.auth.SetupCommand;
import com.streamarr.server.services.auth.SetupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs the REAL first-run setup for dev — bootstrap claim, admin, household, profile, placeholder
 * remap — so dev behaves exactly like a freshly set-up install. Ordered before DevDataInitializer's
 * unordered listener.
 */
@Slf4j
@Component
@Profile("dev")
public class DevIdentitySeeder {

  private final SetupService setupService;
  private final boolean seedIdentity;
  private final String adminEmail;
  private final String adminPassword;

  public DevIdentitySeeder(
      SetupService setupService,
      @Value("${dev.seed-identity:true}") boolean seedIdentity,
      @Value("${dev.admin-email:admin@dev.local}") String adminEmail,
      @Value("${dev.admin-password:streamarr-dev}") String adminPassword) {
    this.setupService = setupService;
    this.seedIdentity = seedIdentity;
    this.adminEmail = adminEmail;
    this.adminPassword = adminPassword;
  }

  @Order(0)
  @EventListener(ApplicationReadyEvent.class)
  public void seedIdentity() {
    if (!seedIdentity) {
      log.info("Dev identity seeding disabled (DEV_SEED_IDENTITY=false).");
      return;
    }
    if (setupService.isSetupComplete()) {
      log.info("Setup already complete; dev identity seeding skipped.");
      return;
    }

    setupService.setup(
        SetupCommand.builder()
            .email(adminEmail)
            .displayName("Dev Admin")
            .password(adminPassword)
            .householdName("Dev Household")
            .profileName("Dev")
            .build());

    log.info("Seeded dev identity — log in with {} and the configured dev password.", adminEmail);
  }
}
