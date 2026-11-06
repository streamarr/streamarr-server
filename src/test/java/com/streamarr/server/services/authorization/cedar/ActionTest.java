package com.streamarr.server.services.authorization.cedar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Action Tests")
class ActionTest {

  @Test
  @DisplayName("Should authorize a read when the Cedar action views something")
  void shouldAuthorizeAReadWhenTheCedarActionViewsSomething() {
    var viewingActions =
        Arrays.stream(Action.values())
            .filter(action -> action.cedarName().startsWith("view"))
            .toList();

    assertThat(viewingActions).isNotEmpty().noneMatch(Action::authorizesWrite);
  }
}
