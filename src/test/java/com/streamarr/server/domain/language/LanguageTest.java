package com.streamarr.server.domain.language;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Language Table Tests")
class LanguageTest {

  @ParameterizedTest
  @ValueSource(strings = {"fr", "fre", "fra", "FRE", " fra "})
  @DisplayName("Should read French when a file tags a stream with any code for French")
  void shouldReadFrenchWhenAFileTagsAStreamWithAnyCodeForFrench(String tag) {
    assertThat(Language.ofFileCode(tag)).contains(Language.FR);
  }

  @ParameterizedTest
  @CsvSource(
      textBlock =
          """
          ger,     DE
          deu,     DE
          chi,     ZH
          zho,     ZH
          cze,     CS
          dut,     NL
          gre,     EL
          iw,      HE
          heb,     HE
          nob,     NB
          """)
  @DisplayName("Should read the language when a file tags a stream with an ISO 639-2 code")
  void shouldReadTheLanguageWhenAFileTagsAStreamWithAnIso6392Code(String tag, Language expected) {
    assertThat(Language.ofFileCode(tag)).contains(expected);
  }

  @ParameterizedTest
  @CsvSource(
      textBlock =
          """
          pt-BR,   PT_BR
          pt_br,   PT_BR
          es-419,  ES_419
          zh-Hans, ZH_HANS
          zh-hant, ZH_HANT
          """)
  @DisplayName("Should read the variant when a file tags a stream with the variant's tag")
  void shouldReadTheVariantWhenAFileTagsAStreamWithTheVariantsTag(String tag, Language expected) {
    assertThat(Language.ofFileCode(tag)).contains(expected);
  }

  @ParameterizedTest
  @CsvSource(
      textBlock =
          """
          pt-PT,   PT
          en-US,   EN
          fre-CA,  FR
          """)
  @DisplayName("Should read the base language when a tag names a region that has no variant")
  void shouldReadTheBaseLanguageWhenATagNamesARegionThatHasNoVariant(
      String tag, Language expected) {
    assertThat(Language.ofFileCode(tag)).contains(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"und", "zxx", "mul", "", "  ", "-", "xx", "english"})
  @DisplayName("Should read no language when a tag names none in the table")
  void shouldReadNoLanguageWhenATagNamesNoneInTheTable(String tag) {
    assertThat(Language.ofFileCode(tag)).isEmpty();
  }

  @Test
  @DisplayName("Should read each value back when a file tags a stream with any of its codes")
  void shouldReadEachValueBackWhenAFileTagsAStreamWithAnyOfItsCodes() {
    for (var language : Language.values()) {
      assertThat(language.fileCodes()).as("%s file codes", language).isNotEmpty();
      for (var code : language.fileCodes()) {
        assertThat(Language.ofFileCode(code)).as("code %s", code).contains(language);
      }
    }
  }

  @Test
  @DisplayName("Should name a distinct base language when a value is a variant")
  void shouldNameADistinctBaseLanguageWhenAValueIsAVariant() {
    assertThat(Language.PT_BR.base()).isEqualTo(Language.PT);
    assertThat(Language.ES_419.base()).isEqualTo(Language.ES);
    assertThat(Language.ZH_HANS.base()).isEqualTo(Language.ZH);
    assertThat(Language.ZH_HANT.base()).isEqualTo(Language.ZH);
    assertThat(Language.FR.base()).isEqualTo(Language.FR);
    assertThat(Arrays.stream(Language.values()).map(Language::base))
        .allMatch(base -> base.base() == base);
  }

  @Test
  @DisplayName("Should give every ISO 639-1 language a value when the table is read")
  void shouldGiveEveryIso6391LanguageAValueWhenTheTableIsRead() {
    var twoLetterValues =
        Arrays.stream(Language.values()).filter(language -> language.name().length() == 2);

    // ISO 639-1 lists 183 current codes once the withdrawn "bh" is left out.
    assertThat(twoLetterValues).hasSize(183);
  }
}
