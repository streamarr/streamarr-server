package com.streamarr.server.graphql.mutation;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.mutation.Outcome;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Mutation Payloads Tests")
class MutationPayloadsTest {

  private record Payload(String result, List<TestError> userErrors) {}

  private record TestError(String message) implements MutationError {}

  @Test
  @DisplayName("Should return the result and no user errors when accepted")
  void shouldReturnResultAndNoUserErrorsWhenAccepted() {
    var payload =
        MutationPayloads.payload(
            Outcome.<String, String>accepted("done"), TestError::new, Payload::new);

    assertThat(payload).isEqualTo(new Payload("done", List.of()));
  }

  @Test
  @DisplayName("Should return no result and one error per rejection when rejected")
  void shouldReturnNoResultAndOneErrorPerRejectionWhenRejected() {
    var payload =
        MutationPayloads.payload(
            Outcome.<String, String>rejected(List.of("a", "b")), TestError::new, Payload::new);

    assertThat(payload)
        .isEqualTo(new Payload(null, List.of(new TestError("a"), new TestError("b"))));
  }

  @Test
  @DisplayName("Should build input paths with decimal list indexes")
  void shouldBuildInputPathsWithDecimalListIndexes() {
    assertThat(InputPath.of("name")).containsExactly("name");
    assertThat(InputPath.element("members", 0, "profileId"))
        .containsExactly("members", "0", "profileId");
  }
}
