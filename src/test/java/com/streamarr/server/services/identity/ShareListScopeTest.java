package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.ProfileShareStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Share list scope")
class ShareListScopeTest {

  @Test
  @DisplayName(
      "Should be the same scope when the same statuses are named in another order or twice")
  void shouldBeTheSameScopeWhenTheSameStatusesAreNamedInAnotherOrderOrTwice() {
    var id = UUID.randomUUID();

    var named =
        new ShareListScope(
            id,
            List.of(
                ProfileShareStatus.PENDING, ProfileShareStatus.ACTIVE, ProfileShareStatus.PENDING));

    assertThat(named)
        .isEqualTo(
            new ShareListScope(id, List.of(ProfileShareStatus.ACTIVE, ProfileShareStatus.PENDING)));
  }
}
