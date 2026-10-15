package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.AuthTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Profile Repository Custom Implementation Integration Tests")
class ProfileRepositoryCustomImplIT extends AbstractIntegrationTest {

  @Autowired private ProfileRepository profileRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("Should store the picture when the Profile exists")
  void shouldStoreThePictureWhenTheProfileExists() {
    var identity = authTestSupport.createIdentity();
    try {
      var profileId = identity.profile().getId();

      var changed = trySetPicture(profileId, "avatar-7");

      assertThat(changed).isTrue();
      assertThat(profileRepository.findById(profileId).orElseThrow().getPicture())
          .isEqualTo("avatar-7");
    } finally {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @DisplayName("Should report no change when the Profile does not exist")
  void shouldReportNoChangeWhenTheProfileDoesNotExist() {
    assertThat(trySetPicture(UUID.randomUUID(), "avatar-7")).isFalse();
  }

  private boolean trySetPicture(UUID profileId, String picture) {
    return Boolean.TRUE.equals(
        transactionTemplate.execute(_ -> profileRepository.trySetPicture(profileId, picture)));
  }
}
