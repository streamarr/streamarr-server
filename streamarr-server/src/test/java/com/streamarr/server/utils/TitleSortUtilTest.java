package com.streamarr.server.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Title Sort Utility Tests")
class TitleSortUtilTest {

  @Test
  @DisplayName("Should strip leading 'The' from title")
  void shouldStripLeadingTheFromTitle() {
    assertThat(TitleSortUtil.computeTitleSort("The Dark Knight")).isEqualTo("Dark Knight, The");
  }

  @Test
  @DisplayName("Should strip leading 'A' from title")
  void shouldStripLeadingAFromTitle() {
    assertThat(TitleSortUtil.computeTitleSort("A Quiet Place")).isEqualTo("Quiet Place, A");
  }

  @Test
  @DisplayName("Should strip leading 'An' from title")
  void shouldStripLeadingAnFromTitle() {
    assertThat(TitleSortUtil.computeTitleSort("An Officer and a Gentleman"))
        .isEqualTo("Officer and a Gentleman, An");
  }

  @Test
  @DisplayName("Should return title unchanged when no leading article")
  void shouldReturnTitleUnchangedWhenNoLeadingArticle() {
    assertThat(TitleSortUtil.computeTitleSort("Inception")).isEqualTo("Inception");
  }

  @Test
  @DisplayName("Should handle null title")
  void shouldHandleNullTitle() {
    assertThat(TitleSortUtil.computeTitleSort(null)).isNull();
  }

  @Test
  @DisplayName("Should handle title that is only an article")
  void shouldHandleTitleThatIsOnlyArticle() {
    assertThat(TitleSortUtil.computeTitleSort("The")).isEqualTo("The");
  }
}
