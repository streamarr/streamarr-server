package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Portable Profile Name Preflight Integration Tests")
class PortableProfileNamePreflightIT extends AbstractIntegrationTest {

  @Autowired private PortableIdentityService portableIdentityService;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private UserAccountRepository accountRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  @DisplayName("Should reject an active household profile name conflict before persistence")
  void shouldRejectActiveHouseholdProfileNameConflictBeforePersistence() {
    var owner = createOwner();
    portableIdentityService.createPortableProfile(profileCommand(owner, "Living Room"));

    assertThatThrownBy(
            () ->
                portableIdentityService.createPortableProfile(profileCommand(owner, "living room")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
  }

  @Test
  @DisplayName("Should reject an active household profile name conflict before rename")
  void shouldRejectActiveHouseholdProfileNameConflictBeforeRename() {
    var owner = createOwner();
    portableIdentityService.createPortableProfile(profileCommand(owner, "Living Room"));
    var renamed =
        portableIdentityService.createPortableProfile(profileCommand(owner, "Guest Room"));

    assertThatThrownBy(
            () ->
                portableIdentityService.renamePortableProfile(
                    RenamePortableProfileCommand.builder()
                        .actingAccountId(owner.getId())
                        .profileId(renamed.getId())
                        .name(" living room ")
                        .build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
  }

  private UserAccount createOwner() {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var household =
                  householdRepository.save(
                      Household.builder().name("Preflight Home " + UUID.randomUUID()).build());
              return accountRepository.save(
                  UserAccount.builder()
                      .email("preflight-owner-" + UUID.randomUUID() + "@example.com")
                      .displayName("Preflight Owner")
                      .passwordHash("encoded")
                      .accountRole(AccountRole.USER)
                      .homeHouseholdId(household.getId())
                      .householdRole(HouseholdRole.OWNER)
                      .build());
            });
  }

  private CreatePortableProfileCommand profileCommand(UserAccount owner, String name) {
    return CreatePortableProfileCommand.builder()
        .actingAccountId(owner.getId())
        .name(name)
        .kind(ProfileKind.ADULT)
        .build();
  }
}
