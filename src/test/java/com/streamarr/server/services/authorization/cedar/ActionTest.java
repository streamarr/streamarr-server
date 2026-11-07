package com.streamarr.server.services.authorization.cedar;

import static java.util.function.Predicate.not;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Action Tests")
class ActionTest {

  @Test
  @DisplayName("Should authorize a read only when the Cedar action views something or plays back")
  void shouldAuthorizeAReadOnlyWhenTheCedarActionViewsSomethingOrPlaysBack() {
    var viewingActions =
        Arrays.stream(Action.values())
            .filter(action -> action.cedarName().startsWith("view"))
            .toList();
    var readActions = Arrays.stream(Action.values()).filter(not(Action::authorizesWrite)).toList();

    assertThat(viewingActions).hasSize(16);
    assertThat(readActions)
        .containsExactlyInAnyOrderElementsOf(
            Stream.concat(viewingActions.stream(), Stream.of(Action.PLAYBACK)).toList());
  }
}
