package com.streamarr.server.services.authorization.cedar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Cedar Engine Self Check Tests")
class CedarEngineSelfCheckTest {

  @Test
  @DisplayName("Should allow the permitted Account and deny a stranger when the engine runs here")
  void shouldAllowPermittedAccountAndDenyStrangerWhenEngineRunsHere() {
    var result = new CedarEngineSelfCheck().run();

    assertThat(result.permittedAccountAllowed()).isTrue();
    assertThat(result.strangerAccountDenied()).isTrue();
    assertThat(result.passed()).isTrue();
  }

  @Test
  @DisplayName("Should report failure when either decision is wrong")
  void shouldReportFailureWhenEitherDecisionIsWrong() {
    assertThat(new CedarEngineSelfCheck.Result(true, false).passed()).isFalse();
    assertThat(new CedarEngineSelfCheck.Result(false, true).passed()).isFalse();
  }
}
