package com.streamarr.server.services.watchprogress.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Watch State Changed Event Tests")
class WatchStateChangedEventTest {

  @Test
  @DisplayName("Should keep its item ids when the caller later changes the given set")
  void shouldKeepItsItemIdsWhenTheCallerLaterChangesTheGivenSet() {
    var itemId = UUID.randomUUID();
    var itemIds = new HashSet<>(Set.of(itemId));

    var event = new WatchStateChangedEvent(UUID.randomUUID(), itemIds);
    itemIds.add(UUID.randomUUID());

    assertThat(event.itemIds()).containsExactly(itemId);
  }

  @Test
  @DisplayName("Should refuse to build when the profile id is missing")
  void shouldRefuseToBuildWhenTheProfileIdIsMissing() {
    var itemIds = Set.of(UUID.randomUUID());

    assertThatThrownBy(() -> new WatchStateChangedEvent(null, itemIds))
        .isInstanceOf(NullPointerException.class)
        .hasMessageStartingWith("profileId ");
  }

  @Test
  @DisplayName("Should refuse to build when the item ids are missing")
  void shouldRefuseToBuildWhenTheItemIdsAreMissing() {
    var profileId = UUID.randomUUID();

    assertThatThrownBy(() -> new WatchStateChangedEvent(profileId, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageStartingWith("itemIds ");
  }
}
