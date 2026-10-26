package com.streamarr.server.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
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

  @ParameterizedTest
  @CsvSource(
      delimiterString = " -> ",
      quoteCharacter = '%',
      value = {
        "The … -> The …",
        "A ... -> A ...",
        "The ! -> The !",
        "An “” -> An “”",
        "The ​ -> The ​",
        "“The …” -> The …”"
      })
  @DisplayName("Should keep the article in place when no letter or digit follows it")
  void shouldKeepArticleInPlaceWhenNoLetterOrDigitFollowsIt(String title, String expected) {
    assertThat(TitleSortUtil.computeTitleSort(title)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "Élite Harbor, Elite Harbor",
    "Ōkura Nights, Okura Nights",
    "Çelik Günler, Celik Gunler",
    "İnci Bay, Inci Bay",
    "Naïve Crossing, Naive Crossing",
    "E\u0301cho Bay, Echo Bay"
  })
  @DisplayName("Should fold Latin diacritics when the title contains accented letters")
  void shouldFoldLatinDiacriticsWhenTitleContainsAccentedLetters(String title, String expected) {
    assertThat(TitleSortUtil.computeTitleSort(title)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"ﬁeld Notes, field Notes", "Ｚｅｎ Garden, Zen Garden", "Ⅻ Gates, XII Gates"})
  @DisplayName("Should decompose compatibility characters when the title contains ligatures")
  void shouldDecomposeCompatibilityCharactersWhenTitleContainsLigatures(
      String title, String expected) {
    assertThat(TitleSortUtil.computeTitleSort(title)).isEqualTo(expected);
  }

  // '%' quotes CSV fields because the cases themselves start with the default quote characters.
  @ParameterizedTest
  @CsvSource(
      delimiterString = " -> ",
      quoteCharacter = '%',
      value = {
        "“Quiet Harbor” -> Quiet Harbor”",
        "”Reversed Quotes“ -> Reversed Quotes“",
        "„Low Quote“ -> Low Quote“",
        "‘Single Harbor’ -> Single Harbor’",
        "'Til Morning -> Til Morning",
        "\"Echo Valley\" -> Echo Valley\"",
        "«Guillemet Road» -> Guillemet Road»",
        "‹Single Guillemet› -> Single Guillemet›",
        "「Corner Bracket」 -> Corner Bracket」",
        "＂Wide Quote＂ -> Wide Quote＂",
        "¡Hola Harbor! -> Hola Harbor!",
        "¿Qué Pasa? -> Que Pasa?",
        "…And Then Rain -> And Then Rain",
        "...And Then Rain -> And Then Rain",
        "#Hashtag Summer -> Hashtag Summer",
        "(12) Steps North -> 12) Steps North",
        "[Bracketed] Night -> Bracketed] Night",
        "*Asterisk Island -> Asterisk Island",
        "@Home Again -> Home Again",
        "_Underscore Lane -> Underscore Lane",
        "-Minus Harbor -> Minus Harbor",
        "+Plus Harbor -> Plus Harbor",
        "^Caret Harbor -> Caret Harbor",
        "`Backtick Harbor -> Backtick Harbor",
        "©Copyright Harbor -> Copyright Harbor",
        "♥Heart Harbor -> Heart Harbor",
        "🦈 Shark Bay -> Shark Bay",
        "\u2764\uFE0F Heart Harbor -> Heart Harbor",
        "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 Family Harbor -> Family Harbor",
        "\uD83C\uDDEF\uD83C\uDDF5 Flag Harbor -> Flag Harbor",
        "\uD83D\uDC4D\uD83C\uDFFD Thumbs Harbor -> Thumbs Harbor",
        "“¡«Nested Openers -> Nested Openers",
        "~Tilde Tales -> Tilde Tales",
        "{Brace} Yourself -> Brace} Yourself",
        "| Pipe Dreams -> Pipe Dreams",
        "~ {Spaced} Out -> Spaced} Out",
        "}}~ Closing Time -> Closing Time"
      })
  @DisplayName("Should strip leading punctuation and symbols when the title starts with them")
  void shouldStripLeadingPunctuationAndSymbolsWhenTitleStartsWithThem(
      String title, String expected) {
    assertThat(TitleSortUtil.computeTitleSort(title)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiterString = " -> ",
      quoteCharacter = '%',
      value = {
        "% Echo Bay% -> Echo Bay",
        "%\tEcho Bay% -> Echo Bay",
        "\u00A0Echo Bay -> Echo Bay",
        "\u3000Echo Bay -> Echo Bay",
        "\u200BEcho Bay -> Echo Bay",
        "\uFEFFEcho Bay -> Echo Bay",
        "\u200EEcho Bay -> Echo Bay",
        "\u0301Echo Bay -> Echo Bay",
        "\uFEFF“Quiet Harbor” -> Quiet Harbor”",
        "“\u00A0Quiet Harbor” -> Quiet Harbor”"
      })
  @DisplayName("Should strip leading invisible characters when the title starts with them")
  void shouldStripLeadingInvisibleCharactersWhenTitleStartsWithThem(String title, String expected) {
    assertThat(TitleSortUtil.computeTitleSort(title)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiterString = " -> ",
      quoteCharacter = '%',
      value = {
        "$ilver Harbor -> $ilver Harbor",
        "€cho Valley -> €cho Valley",
        "¢ent Street -> ¢ent Street",
        "$Dollar Days -> $Dollar Days",
        "$9.99 Harbor -> $9.99 Harbor",
        "“$ilver Harbor” -> $ilver Harbor”",
        "\uFEFF$ilver Harbor -> $ilver Harbor"
      })
  @DisplayName("Should keep a leading currency sign when the title starts with one")
  void shouldKeepLeadingCurrencySignWhenTitleStartsWithOne(String title, String expected) {
    assertThat(TitleSortUtil.computeTitleSort(title)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"~~~", "{ }", "|", "“”", "...", "!?!", "🦈", "\u200B", "\u0301", "\uFEFF“”"})
  @DisplayName("Should keep the title when no letter or digit remains")
  void shouldKeepTitleWhenNoLetterOrDigitRemains(String title) {
    assertThat(TitleSortUtil.computeTitleSort(title)).isEqualTo(title);
  }

  @ParameterizedTest
  @CsvSource(
      delimiterString = " -> ",
      quoteCharacter = '%',
      value = {
        "Echo: Valley! -> Echo: Valley!",
        "Harbor (Director’s Cut) -> Harbor (Director’s Cut)",
        "Quiet—Harbor -> Quiet—Harbor",
        "2 Fast Harbor -> 2 Fast Harbor",
        "1917 Echo -> 1917 Echo",
        "#1 Fan Club -> 1 Fan Club",
        "“The” Harbor -> The” Harbor"
      })
  @DisplayName("Should keep every character from the first letter or digit when stripping")
  void shouldKeepEveryCharacterFromTheFirstLetterOrDigitWhenStripping(
      String title, String expected) {
    assertThat(TitleSortUtil.computeTitleSort(title)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiterString = " -> ",
      quoteCharacter = '%',
      value = {
        "“The Harbor” -> Harbor”, The",
        "¡A Quiet Night! -> Quiet Night!, A",
        "«An Echo» -> Echo», An",
        "\uFEFFThe Harbor -> Harbor, The"
      })
  @DisplayName("Should move the article when it follows leading punctuation")
  void shouldMoveArticleWhenItFollowsLeadingPunctuation(String title, String expected) {
    assertThat(TitleSortUtil.computeTitleSort(title)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource(
      delimiterString = " -> ",
      quoteCharacter = '%',
      value = {
        "「東京の空」 -> 東京の空」",
        "«Жёлтый Дом» -> Жёлтый Дом»",
        "¿Ώρα Μηδέν? -> Ώρα Μηδέν?",
        "“한강의 밤” -> 한강의 밤”"
      })
  @DisplayName("Should strip only the leading punctuation when a non-Latin title starts with it")
  void shouldStripOnlyTheLeadingPunctuationWhenNonLatinTitleStartsWithIt(
      String title, String expected) {
    assertThat(TitleSortUtil.computeTitleSort(title)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "The Élite, 'Elite, The'",
    "An Ōkura Night, 'Okura Night, An'",
    "~The Harbor, 'Harbor, The'",
    "The ~Harbor, 'Harbor, The'",
    "The “Harbor”, 'Harbor”, The'",
    "The Ⅻ, 'XII, The'",
    "The $, '$, The'",
    "The “$ilver”, '$ilver”, The'"
  })
  @DisplayName("Should move the article after normalizing when the title starts with an article")
  void shouldMoveArticleAfterNormalizingWhenTitleStartsWithArticle(String title, String expected) {
    assertThat(TitleSortUtil.computeTitleSort(title)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "\u00C0 bout de souffle, A bout de souffle",
    "A\u0300 bout de souffle, A bout de souffle",
    "\u00C0 la folie, A la folie",
    "\u00C1 Winter Tale, A Winter Tale",
    "\u00C5 Song, A Song",
    "\u0102n Tr\u01B0a, An Trua",
    "\u00C2n Harbor, An Harbor",
    "Th\u00E9 au harem, The au harem",
    "The\u0301 au harem, The au harem"
  })
  @DisplayName("Should keep the first word in place when only its folded form is an article")
  void shouldKeepFirstWordInPlaceWhenOnlyItsFoldedFormIsAnArticle(String title, String expected) {
    assertThat(TitleSortUtil.computeTitleSort(title)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Жёлтый Дом",
        "がっこう日記",
        "한강의 밤",
        "Ώρα Μηδέν",
        "東京の空",
        "น้ำตกสีทอง",
        "ｶﾞｯｺｳ ﾆｯｷ",
        "ﻻ نهاية",
        "東京（下）"
      })
  @DisplayName("Should leave the title unchanged when it is written in a non-Latin script")
  void shouldLeaveTitleUnchangedWhenItIsWrittenInNonLatinScript(String title) {
    assertThat(TitleSortUtil.computeTitleSort(title)).isEqualTo(title);
  }
}
