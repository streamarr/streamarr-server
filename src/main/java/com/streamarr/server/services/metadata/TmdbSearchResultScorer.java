package com.streamarr.server.services.metadata;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TmdbSearchResultScorer {

  private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();

  private static final double TITLE_WEIGHT = 0.6;
  private static final double YEAR_WEIGHT = 0.3;
  private static final double POPULARITY_WEIGHT = 0.1;

  private static final double MINIMUM_TITLE_SIMILARITY = 0.55;
  private static final double MINIMUM_OVERALL_SCORE = 0.3;

  private static final double POPULARITY_CEILING = 1000.0;

  private static final double JARO_WINKLER_WEIGHT = 0.4;
  private static final double BIGRAM_DICE_WEIGHT = 0.35;
  private static final double BIGRAM_JACCARD_WEIGHT = 0.25;

  public record CandidateResult(
      String title, String originalTitle, String year, double popularity) {}

  public static OptionalInt selectBestMatch(
      String parsedTitle, String parsedYear, List<CandidateResult> candidates) {
    var normalizedParsed = normalizeTitle(parsedTitle);
    if (normalizedParsed.isEmpty()) {
      return OptionalInt.empty();
    }

    var bestIndex = -1;
    var bestScore = -1.0;

    for (var i = 0; i < candidates.size(); i++) {
      var candidate = candidates.get(i);
      var score = scoreCandidate(normalizedParsed, parsedYear, candidate);

      if (score > bestScore) {
        bestScore = score;
        bestIndex = i;
      }
    }

    if (bestIndex >= 0 && bestScore >= MINIMUM_OVERALL_SCORE) {
      return OptionalInt.of(bestIndex);
    }

    return OptionalInt.empty();
  }

  private static double scoreCandidate(
      String normalizedParsed, String parsedYear, CandidateResult candidate) {
    var titleSim = computeTitleSimilarity(normalizedParsed, candidate);

    if (titleSim < MINIMUM_TITLE_SIMILARITY) {
      return 0.0;
    }

    var yearMatch = computeYearMatch(parsedYear, candidate.year());
    var popScore = computePopularityScore(candidate.popularity());

    return (TITLE_WEIGHT * titleSim) + (YEAR_WEIGHT * yearMatch) + (POPULARITY_WEIGHT * popScore);
  }

  private static double computeTitleSimilarity(String normalizedParsed, CandidateResult candidate) {
    var normalizedTitle = normalizeTitle(candidate.title());
    var normalizedOriginal = normalizeTitle(candidate.originalTitle());

    var titleScore =
        normalizedTitle.isEmpty() ? 0.0 : ensembleScore(normalizedParsed, normalizedTitle);
    var originalScore =
        normalizedOriginal.isEmpty() ? 0.0 : ensembleScore(normalizedParsed, normalizedOriginal);

    return Math.max(titleScore, originalScore);
  }

  private static double ensembleScore(String a, String b) {
    return JARO_WINKLER_WEIGHT * JARO_WINKLER.apply(a, b)
        + BIGRAM_DICE_WEIGHT * bigramDice(a, b)
        + BIGRAM_JACCARD_WEIGHT * bigramJaccard(a, b);
  }

  private static String normalizeTitle(String input) {
    if (input == null || input.isBlank()) {
      return "";
    }

    var normalized = Normalizer.normalize(input, Normalizer.Form.NFKD);
    normalized = normalized.toLowerCase();
    normalized = normalized.replaceFirst("^(the|a|an)\\s+", "");
    normalized = normalized.replaceAll("[^a-z0-9\\s]", "");
    normalized = normalized.replaceAll("\\s+", " ").strip();

    return normalized;
  }

  private static double bigramDice(String a, String b) {
    if (a.length() < 2 || b.length() < 2) {
      return a.equals(b) ? 1.0 : 0.0;
    }

    var bigramsA = extractBigrams(a);
    var bigramsB = extractBigrams(b);

    var intersection = 0;
    var bigramsBCopy = new HashMap<>(bigramsB);

    for (var entry : bigramsA.entrySet()) {
      var countInB = bigramsBCopy.getOrDefault(entry.getKey(), 0);
      var overlap = Math.min(entry.getValue(), countInB);
      intersection += overlap;
    }

    var totalSize =
        bigramsA.values().stream().mapToInt(Integer::intValue).sum()
            + bigramsB.values().stream().mapToInt(Integer::intValue).sum();

    return (2.0 * intersection) / totalSize;
  }

  private static double bigramJaccard(String a, String b) {
    if (a.length() < 2 || b.length() < 2) {
      return a.equals(b) ? 1.0 : 0.0;
    }

    var bigramsA = extractBigrams(a);
    var bigramsB = extractBigrams(b);

    var intersection = 0;
    var union = 0;

    var allKeys = new HashMap<>(bigramsA);
    for (var entry : bigramsB.entrySet()) {
      allKeys.merge(entry.getKey(), entry.getValue(), Integer::max);
    }

    for (var entry : allKeys.entrySet()) {
      var countA = bigramsA.getOrDefault(entry.getKey(), 0);
      var countB = bigramsB.getOrDefault(entry.getKey(), 0);
      intersection += Math.min(countA, countB);
      union += Math.max(countA, countB);
    }

    return union == 0 ? 0.0 : (double) intersection / union;
  }

  private static Map<String, Integer> extractBigrams(String s) {
    var bigrams = new HashMap<String, Integer>();
    for (var i = 0; i < s.length() - 1; i++) {
      var bigram = s.substring(i, i + 2);
      bigrams.merge(bigram, 1, Integer::sum);
    }
    return bigrams;
  }

  private static double computeYearMatch(String parsedYear, String candidateYear) {
    if (parsedYear == null || candidateYear == null) {
      return 0.0;
    }

    var parsedYearStr = extractYear(parsedYear);
    var candidateYearStr = extractYear(candidateYear);

    return parsedYearStr.equals(candidateYearStr) ? 1.0 : 0.0;
  }

  private static String extractYear(String dateOrYear) {
    if (dateOrYear.length() >= 4) {
      return dateOrYear.substring(0, 4);
    }
    return dateOrYear;
  }

  private static double computePopularityScore(double popularity) {
    return Math.min(1.0, Math.log10(1 + popularity) / Math.log10(1 + POPULARITY_CEILING));
  }
}
