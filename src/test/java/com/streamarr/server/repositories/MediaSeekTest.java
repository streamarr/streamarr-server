package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class MediaSeekTest {

  @Test
  void shouldCompareEquivalentOrderingByValue() {
    var first = MediaSeek.builder().orderByColumns(List.of(DSL.field("title").asc())).build();
    var second = MediaSeek.builder().orderByColumns(List.of(DSL.field("title").asc())).build();

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }
}
