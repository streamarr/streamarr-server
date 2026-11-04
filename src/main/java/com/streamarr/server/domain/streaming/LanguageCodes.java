package com.streamarr.server.domain.streaming;

import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;

// Files tag a stream's language with an ISO 639-1 code, or with either of the two ISO 639-2 codes
// that 20 languages have. Comparing tracks' languages needs one code per language.
final class LanguageCodes {

  // ISO 639-2 bibliographic codes and the terminological code of the same language.
  private static final Map<String, String> TERMINOLOGICAL_BY_BIBLIOGRAPHIC =
      Map.ofEntries(
          Map.entry("alb", "sqi"),
          Map.entry("arm", "hye"),
          Map.entry("baq", "eus"),
          Map.entry("bur", "mya"),
          Map.entry("chi", "zho"),
          Map.entry("cze", "ces"),
          Map.entry("dut", "nld"),
          Map.entry("fre", "fra"),
          Map.entry("geo", "kat"),
          Map.entry("ger", "deu"),
          Map.entry("gre", "ell"),
          Map.entry("ice", "isl"),
          Map.entry("mac", "mkd"),
          Map.entry("mao", "mri"),
          Map.entry("may", "msa"),
          Map.entry("per", "fas"),
          Map.entry("rum", "ron"),
          Map.entry("slo", "slk"),
          Map.entry("tib", "bod"),
          Map.entry("wel", "cym"));

  private LanguageCodes() {}

  /**
   * The ISO 639-2 terminological code of the language that the tag names, or the tag lowercased.
   */
  static String canonical(String tag) {
    var code = tag.strip().toLowerCase(Locale.ROOT);
    if (code.length() == 2) {
      code = terminologicalOf(code);
    }

    return TERMINOLOGICAL_BY_BIBLIOGRAPHIC.getOrDefault(code, code);
  }

  private static String terminologicalOf(String twoLetterCode) {
    try {
      return Locale.of(twoLetterCode).getISO3Language();
    } catch (MissingResourceException _) {
      return twoLetterCode;
    }
  }
}
