package com.streamarr.server.services.parsers.show;

import com.streamarr.server.services.parsers.MetadataParser;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import org.springframework.stereotype.Service;

@Service
public class SeasonPathMetadataParser implements MetadataParser<SeasonPathMetadataParser.Result> {

  private static final List<String> SEASON_FOLDER_NAMES =
      List.of("season", "sæson", "temporada", "saison", "staffel", "series", "сезон", "stagione");

  public record Result(OptionalInt seasonNumber, boolean isSeasonFolder) {
    @Builder
    // compact constructor for @Builder
    public Result {}
  }

  public Optional<Result> parse(String folderName) {
    if (folderName.isBlank()) {
      return Optional.of(
          Result.builder().seasonNumber(OptionalInt.empty()).isSeasonFolder(false).build());
    }

    return Optional.of(getSeasonNumberFromFolderName(folderName));
  }

  private Result getSeasonNumberFromFolderName(String folderName) {
    var directoryName = folderName.toLowerCase();

    var specialAliasResult = evaluatePathForSpecialAliases(directoryName);

    if (specialAliasResult.isPresent()) {
      return specialAliasResult.get();
    }

    var numericFolderResult = evaluatePathForNumericSeasonFolders(directoryName);

    if (numericFolderResult.isPresent()) {
      return numericFolderResult.get();
    }

    var shortNameResult = evaluatePathForOptimisticShortName(directoryName);

    if (shortNameResult.isPresent()) {
      return shortNameResult.get();
    }

    var folderEvaluationResult = evaluatePathUsingFolderNames(directoryName);

    if (folderEvaluationResult.isPresent()) {
      return folderEvaluationResult.get();
    }

    var parts = directoryName.split("[._ -]", -1);
    var partsEvaluationResult = evaluatePathUsingParts(parts);

    return partsEvaluationResult.orElseGet(
        () -> Result.builder().seasonNumber(OptionalInt.empty()).isSeasonFolder(false).build());
  }

  private Optional<Result> evaluatePathForSpecialAliases(String path) {
    var trimmedPath = path.trim();

    if (!trimmedPath.equals("specials") && !trimmedPath.equals("extras")) {
      return Optional.empty();
    }

    return Optional.of(
        Result.builder().seasonNumber(OptionalInt.of(0)).isSeasonFolder(true).build());
  }

  private Optional<Result> evaluatePathForNumericSeasonFolders(String path) {
    var result = tryStringToOptionalIntConversion(path);

    if (result.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(Result.builder().seasonNumber(result).isSeasonFolder(true).build());
  }

  private OptionalInt tryStringToOptionalIntConversion(String input) {
    try {
      return OptionalInt.of(Integer.parseInt(input));
    } catch (NumberFormatException _) {
      return OptionalInt.empty();
    }
  }

  private Optional<Result> evaluatePathForOptimisticShortName(String path) {
    if (!path.startsWith("s")) {
      return Optional.empty();
    }

    var seasonNumberString = path.substring(1);

    var result = tryStringToOptionalIntConversion(seasonNumberString);

    if (result.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(Result.builder().seasonNumber(result).isSeasonFolder(true).build());
  }

  private Optional<Result> evaluatePathUsingFolderNames(String path) {
    for (var name : SEASON_FOLDER_NAMES) {
      if (!path.contains(name)) {
        continue;
      }

      var result = getSeasonNumberFromPathSubstring(path.replace(name, " "));

      if (result.isEmpty()) {
        continue;
      }

      return result;
    }

    return Optional.empty();
  }

  private Optional<Result> getSeasonNumberFromPathSubstring(String path) {
    var numericStart = -1;
    var length = 0;

    var hasOpenParenthesis = false;
    var isSeasonFolder = true;

    for (var i = 0; i < path.length(); i++) {
      var ch = path.charAt(i);

      var numericOutsideParentheses = Character.isDigit(ch) && !hasOpenParenthesis;
      if (numericOutsideParentheses && numericStart == -1) {
        numericStart = i;
      }

      if (numericOutsideParentheses) {
        length++;
        continue;
      }

      if (numericStart != -1) {
        isSeasonFolder = false;
        break;
      }

      if (ch == '(') {
        hasOpenParenthesis = true;
        continue;
      }

      if (ch == ')') {
        hasOpenParenthesis = false;
      }
    }

    if (numericStart == -1) {
      return Optional.of(
          Result.builder().seasonNumber(OptionalInt.empty()).isSeasonFolder(false).build());
    }

    var optionalSeasonNumber =
        tryStringToOptionalIntConversion(path.substring(numericStart, length + numericStart));

    if (optionalSeasonNumber.isPresent()) {
      return Optional.of(
          Result.builder()
              .seasonNumber(optionalSeasonNumber)
              .isSeasonFolder(isSeasonFolder)
              .build());
    }

    return Optional.empty();
  }

  private Optional<Result> evaluatePathUsingParts(String[] parts) {
    for (var part : parts) {
      var seasonNumber = tryGetSeasonNumberFromPart(part);

      if (seasonNumber.isEmpty()) {
        continue;
      }

      return Optional.of(Result.builder().seasonNumber(seasonNumber).isSeasonFolder(true).build());
    }

    return Optional.empty();
  }

  private OptionalInt tryGetSeasonNumberFromPart(String part) {
    if (part.length() < 2 || !part.startsWith("s")) {
      return OptionalInt.empty();
    }

    return tryStringToOptionalIntConversion(part.substring(1));
  }
}
