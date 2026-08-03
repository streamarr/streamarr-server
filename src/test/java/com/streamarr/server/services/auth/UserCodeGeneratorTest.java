package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("User Code Generator Tests")
class UserCodeGeneratorTest {

  private final UserCodeGenerator generator = new UserCodeGenerator();

  @Test
  @DisplayName("Should mint codes that pass the contract's own normalization")
  void shouldMintCodesThatPassContractsOwnNormalization() {
    IntStream.range(0, 500)
        .forEach(
            _ -> {
              var code = generator.generate();
              assertThat(code).hasSize(UserCode.LENGTH);
              assertThatCode(() -> UserCode.normalize(code)).doesNotThrowAnyException();
            });
  }

  @Test
  @DisplayName("Should draw on the whole alphabet rather than a subset")
  void shouldDrawOnWholeAlphabetRatherThanSubset() {
    var seen =
        IntStream.range(0, 2000)
            .mapToObj(_ -> generator.generate())
            .flatMap(code -> code.chars().mapToObj(character -> (char) character))
            .distinct()
            .toList();

    assertThat(seen).hasSize(UserCode.ALPHABET.length());
  }

  @Test
  @DisplayName("Should not repeat itself across successive codes")
  void shouldNotRepeatItselfAcrossSuccessiveCodes() {
    var codes = IntStream.range(0, 500).mapToObj(_ -> generator.generate()).distinct().toList();

    assertThat(codes).hasSize(500);
  }
}
