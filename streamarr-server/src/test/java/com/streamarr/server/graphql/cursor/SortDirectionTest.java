package com.streamarr.server.graphql.cursor;

import static org.assertj.core.api.Assertions.assertThat;

import org.jooq.SortOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Sort Direction Tests")
class SortDirectionTest {

  @Test
  @DisplayName("Should return jOOQ DESC when SortDirection is DESC")
  void shouldReturnJooqDescWhenSortDirectionIsDesc() {
    assertThat(SortDirection.DESC.toSortOrder()).isEqualTo(SortOrder.DESC);
  }

  @Test
  @DisplayName("Should return jOOQ ASC when SortDirection is ASC")
  void shouldReturnJooqAscWhenSortDirectionIsAsc() {
    assertThat(SortDirection.ASC.toSortOrder()).isEqualTo(SortOrder.ASC);
  }
}
