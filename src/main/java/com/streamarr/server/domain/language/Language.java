package com.streamarr.server.domain.language;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The languages that a Profile's language preferences name (ADR 0043). The table lists every ISO
 * 639-1 language with the codes that files tag a stream with: its ISO 639-1 code, its ISO 639-2
 * code and, where it differs, its ISO 639-2 bibliographic code or its withdrawn ISO 639-1 code. A
 * variant names its BCP 47 tag and its base language. The GraphQL {@code Language} enum is
 * generated from this table.
 */
public enum Language {
  AA("aa", "aar"),
  AB("ab", "abk"),
  AE("ae", "ave"),
  AF("af", "afr"),
  AK("ak", "aka"),
  AM("am", "amh"),
  AN("an", "arg"),
  AR("ar", "ara"),
  AS("as", "asm"),
  AV("av", "ava"),
  AY("ay", "aym"),
  AZ("az", "aze"),
  BA("ba", "bak"),
  BE("be", "bel"),
  BG("bg", "bul"),
  BI("bi", "bis"),
  BM("bm", "bam"),
  BN("bn", "ben"),
  BO("bo", "bod", "tib"),
  BR("br", "bre"),
  BS("bs", "bos"),
  CA("ca", "cat"),
  CE("ce", "che"),
  CH("ch", "cha"),
  CO("co", "cos"),
  CR("cr", "cre"),
  CS("cs", "ces", "cze"),
  CU("cu", "chu"),
  CV("cv", "chv"),
  CY("cy", "cym", "wel"),
  DA("da", "dan"),
  DE("de", "deu", "ger"),
  DV("dv", "div"),
  DZ("dz", "dzo"),
  EE("ee", "ewe"),
  EL("el", "ell", "gre"),
  EN("en", "eng"),
  EO("eo", "epo"),
  ES("es", "spa"),
  ET("et", "est"),
  EU("eu", "eus", "baq"),
  FA("fa", "fas", "per"),
  FF("ff", "ful"),
  FI("fi", "fin"),
  FJ("fj", "fij"),
  FO("fo", "fao"),
  FR("fr", "fra", "fre"),
  FY("fy", "fry"),
  GA("ga", "gle"),
  GD("gd", "gla"),
  GL("gl", "glg"),
  GN("gn", "grn"),
  GU("gu", "guj"),
  GV("gv", "glv"),
  HA("ha", "hau"),
  HE("he", "heb", "iw"),
  HI("hi", "hin"),
  HO("ho", "hmo"),
  HR("hr", "hrv"),
  HT("ht", "hat"),
  HU("hu", "hun"),
  HY("hy", "hye", "arm"),
  HZ("hz", "her"),
  IA("ia", "ina"),
  ID("id", "ind", "in"),
  IE("ie", "ile"),
  IG("ig", "ibo"),
  II("ii", "iii"),
  IK("ik", "ipk"),
  IO("io", "ido"),
  IS("is", "isl", "ice"),
  IT("it", "ita"),
  IU("iu", "iku"),
  JA("ja", "jpn"),
  JV("jv", "jav"),
  KA("ka", "kat", "geo"),
  KG("kg", "kon"),
  KI("ki", "kik"),
  KJ("kj", "kua"),
  KK("kk", "kaz"),
  KL("kl", "kal"),
  KM("km", "khm"),
  KN("kn", "kan"),
  KO("ko", "kor"),
  KR("kr", "kau"),
  KS("ks", "kas"),
  KU("ku", "kur"),
  KV("kv", "kom"),
  KW("kw", "cor"),
  KY("ky", "kir"),
  LA("la", "lat"),
  LB("lb", "ltz"),
  LG("lg", "lug"),
  LI("li", "lim"),
  LN("ln", "lin"),
  LO("lo", "lao"),
  LT("lt", "lit"),
  LU("lu", "lub"),
  LV("lv", "lav"),
  MG("mg", "mlg"),
  MH("mh", "mah"),
  MI("mi", "mri", "mao"),
  MK("mk", "mkd", "mac"),
  ML("ml", "mal"),
  MN("mn", "mon"),
  MR("mr", "mar"),
  MS("ms", "msa", "may"),
  MT("mt", "mlt"),
  MY("my", "mya", "bur"),
  NA("na", "nau"),
  NB("nb", "nob"),
  ND("nd", "nde"),
  NE("ne", "nep"),
  NG("ng", "ndo"),
  NL("nl", "nld", "dut"),
  NN("nn", "nno"),
  NO("no", "nor"),
  NR("nr", "nbl"),
  NV("nv", "nav"),
  NY("ny", "nya"),
  OC("oc", "oci"),
  OJ("oj", "oji"),
  OM("om", "orm"),
  OR("or", "ori"),
  OS("os", "oss"),
  PA("pa", "pan"),
  PI("pi", "pli"),
  PL("pl", "pol"),
  PS("ps", "pus"),
  PT("pt", "por"),
  QU("qu", "que"),
  RM("rm", "roh"),
  RN("rn", "run"),
  RO("ro", "ron", "rum"),
  RU("ru", "rus"),
  RW("rw", "kin"),
  SA("sa", "san"),
  SC("sc", "srd"),
  SD("sd", "snd"),
  SE("se", "sme"),
  SG("sg", "sag"),
  SI("si", "sin"),
  SK("sk", "slk", "slo"),
  SL("sl", "slv"),
  SM("sm", "smo"),
  SN("sn", "sna"),
  SO("so", "som"),
  SQ("sq", "sqi", "alb"),
  SR("sr", "srp"),
  SS("ss", "ssw"),
  ST("st", "sot"),
  SU("su", "sun"),
  SV("sv", "swe"),
  SW("sw", "swa"),
  TA("ta", "tam"),
  TE("te", "tel"),
  TG("tg", "tgk"),
  TH("th", "tha"),
  TI("ti", "tir"),
  TK("tk", "tuk"),
  TL("tl", "tgl"),
  TN("tn", "tsn"),
  TO("to", "ton"),
  TR("tr", "tur"),
  TS("ts", "tso"),
  TT("tt", "tat"),
  TW("tw", "twi"),
  TY("ty", "tah"),
  UG("ug", "uig"),
  UK("uk", "ukr"),
  UR("ur", "urd"),
  UZ("uz", "uzb"),
  VE("ve", "ven"),
  VI("vi", "vie"),
  VO("vo", "vol"),
  WA("wa", "wln"),
  WO("wo", "wol"),
  XH("xh", "xho"),
  YI("yi", "yid", "ji"),
  YO("yo", "yor"),
  ZA("za", "zha"),
  ZH("zh", "zho", "chi"),
  ZU("zu", "zul"),
  PT_BR(PT, "pt-BR"),
  ES_419(ES, "es-419"),
  ZH_HANS(ZH, "zh-Hans"),
  ZH_HANT(ZH, "zh-Hant");

  private static final Map<String, Language> BY_FILE_CODE =
      Arrays.stream(values())
          .flatMap(language -> language.fileCodes().stream().map(code -> Map.entry(code, language)))
          .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

  private final Language variantOf;
  private final String tag;
  private final List<String> fileCodes;

  Language(String twoLetterCode, String... otherCodes) {
    this.variantOf = null;
    this.tag = twoLetterCode;
    this.fileCodes = Stream.concat(Stream.of(twoLetterCode), Arrays.stream(otherCodes)).toList();
  }

  Language(Language base, String tag) {
    this.variantOf = base;
    this.tag = tag;
    this.fileCodes = List.of(normalized(tag));
  }

  /**
   * The value whose code a file's stream tag names. A tag that names a region or script without a
   * variant in the table, such as {@code pt-PT}, reads as its base language. Empty for a tag that
   * names no language in the table, such as {@code und}.
   */
  public static Optional<Language> ofFileCode(String tag) {
    var code = normalized(tag);
    var exact = Optional.ofNullable(BY_FILE_CODE.get(code));
    if (exact.isPresent() || code.indexOf('-') <= 0) {
      return exact;
    }

    return Optional.ofNullable(BY_FILE_CODE.get(code.substring(0, code.indexOf('-'))));
  }

  /** The BCP 47 tag of the value, such as {@code fr} or {@code pt-BR}. */
  public String tag() {
    return tag;
  }

  /** The codes, in lower case, that files tag a stream in this language with. */
  public List<String> fileCodes() {
    return fileCodes;
  }

  /** The language that a variant belongs to; a value that is no variant is its own base. */
  public Language base() {
    return Optional.ofNullable(variantOf).orElse(this);
  }

  private static String normalized(String tag) {
    return tag.strip().replace('_', '-').toLowerCase(Locale.ROOT);
  }
}
