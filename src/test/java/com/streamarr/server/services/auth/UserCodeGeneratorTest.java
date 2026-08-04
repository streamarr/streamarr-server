package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("User Code Generator Tests")
class UserCodeGeneratorTest {

  private final UserCodeGenerator generator = new UserCodeGenerator();

  @Test
  @DisplayName("Should mint a code in the user-code grammar")
  void shouldMintCodeInUserCodeGrammar() {
    var code = generator.generate();

    assertThat(code).hasSize(UserCode.LENGTH);
    assertThatCode(() -> UserCode.normalize(code)).doesNotThrowAnyException();
  }
}
