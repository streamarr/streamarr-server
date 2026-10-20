package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.pagination.MediaFilter;
import java.util.List;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class MediaSeekTest {

  @Test
  @DisplayName("Should treat seeks as equal when their orderings are equal by value")
  void shouldTreatSeeksAsEqualWhenTheirOrderingsAreEqualByValue() {
    var first = seekByTitle().build();
    var second = seekByTitle().build();

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  private static MediaSeek.MediaSeekBuilder seekByTitle() {
    return MediaSeek.builder()
        .filter(MediaFilter.builder().build())
        .sortColumn(DSL.field("title"))
        .orderByColumns(List.of(DSL.field("title").asc()));
  }
}
