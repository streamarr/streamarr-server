package com.streamarr.server.graphql.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Watch State Change Tests")
class WatchStateChangeTest {

  @Test
  @DisplayName("Should keep its item ids when the caller later changes the set it passed")
  void shouldKeepItsItemIdsWhenCallerLaterChangesSetItPassed() {
    var itemId = UUID.randomUUID();
    var itemIds = new HashSet<UUID>();
    itemIds.add(itemId);
    var change = new WatchStateChange(itemIds);

    itemIds.add(UUID.randomUUID());

    assertThat(change.itemIds()).containsExactly(itemId);
  }

  @Test
  @DisplayName("Should refuse to build when the item ids are missing")
  void shouldRefuseToBuildWhenItemIdsAreMissing() {
    assertThatThrownBy(() -> new WatchStateChange(null)).isInstanceOf(NullPointerException.class);
  }
}
