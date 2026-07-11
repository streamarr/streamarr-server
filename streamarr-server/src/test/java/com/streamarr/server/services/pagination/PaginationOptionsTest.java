package com.streamarr.server.services.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("PaginationOptions Tests")
class PaginationOptionsTest {

  @Test
  @DisplayName("Should default to forward when pagination direction is null")
  void shouldDefaultToForwardWhenPaginationDirectionIsNull() {
    var options = PaginationOptions.builder().cursor(Optional.empty()).limit(10).build();

    assertThat(options.getPaginationDirection()).isEqualTo(PaginationDirection.FORWARD);
  }
}
