package com.streamarr.server.services.pagination;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.Movie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("PageItem Tests")
class PageItemTest {

  private static final Movie STUB_ENTITY = Movie.builder().build();

  @Nested
  @DisplayName("Allowed Types")
  class AllowedTypes {

    @Test
    @DisplayName("Should accept null sort value")
    void shouldAcceptNullSortValue() {
      assertThatNoException().isThrownBy(() -> new PageItem<>(STUB_ENTITY, null));
    }

    @Test
    @DisplayName("Should accept String sort value")
    void shouldAcceptStringSortValue() {
      assertThatNoException().isThrownBy(() -> new PageItem<>(STUB_ENTITY, "title"));
    }

    @Test
    @DisplayName("Should accept LocalDate sort value")
    void shouldAcceptLocalDateSortValue() {
      assertThatNoException().isThrownBy(() -> new PageItem<>(STUB_ENTITY, LocalDate.now()));
    }

    @Test
    @DisplayName("Should accept Instant sort value")
    void shouldAcceptInstantSortValue() {
      assertThatNoException().isThrownBy(() -> new PageItem<>(STUB_ENTITY, Instant.now()));
    }
  }

  @Nested
  @DisplayName("Rejected Types")
  class RejectedTypes {

    @Test
    @DisplayName("Should throw when sort value type is unsupported")
    void shouldThrowWhenSortValueTypeIsUnsupported() {
      var unsupportedValue = new BigDecimal("1.0");

      assertThatThrownBy(() -> new PageItem<>(STUB_ENTITY, unsupportedValue))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("BigDecimal");
    }
  }
}
