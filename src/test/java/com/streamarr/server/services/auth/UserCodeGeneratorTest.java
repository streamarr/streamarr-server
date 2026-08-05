package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("User Code Generator Tests")
class UserCodeGeneratorTest {

  @Test
  @DisplayName("Should build the code from eight bounded random selections")
  void shouldBuildCodeFromEightBoundedRandomSelections() {
    var selection = new AtomicInteger();
    var generator = new UserCodeGenerator(indexedRandom(selection));

    var code = generator.generate();

    assertThat(code).isEqualTo("BCDFGHJK");
    assertThat(selection).hasValue(UserCode.LENGTH);
  }

  private static SecureRandom indexedRandom(AtomicInteger selection) {
    return new SecureRandom() {
      @Override
      public int nextInt(int bound) {
        assertThat(bound).isEqualTo(UserCode.ALPHABET.length());
        return selection.getAndIncrement();
      }
    };
  }
}
