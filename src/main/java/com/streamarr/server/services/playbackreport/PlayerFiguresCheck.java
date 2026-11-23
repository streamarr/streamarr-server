package com.streamarr.server.services.playbackreport;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Predicate;

/** Names each player figure whose value no player reports. */
final class PlayerFiguresCheck {

  /** The longest text, in Unicode code points, that a figure such as a codec name holds. */
  private static final int MAXIMUM_TEXT_LENGTH = 64;

  private PlayerFiguresCheck() {}

  static List<PlayerFigure> invalidFigures(PlayerFigures figures) {
    var invalid = new ArrayList<PlayerFigure>();
    check(invalid, PlayerFigure.QUALITY_STEP, figures.qualityStep(), PlayerFiguresCheck::isValid);
    checkDouble(invalid, PlayerFigure.OBSERVED_BITRATE, figures.observedBitrate());
    checkDouble(invalid, PlayerFigure.BUFFER_SECONDS, figures.bufferSeconds());
    checkCount(invalid, PlayerFigure.DROPPED_FRAMES, figures.droppedFrames());
    checkCount(invalid, PlayerFigure.STALLS, figures.stalls());
    check(
        invalid,
        PlayerFigure.RESOLUTION,
        figures.resolution(),
        size -> size.width() > 0 && size.height() > 0);
    check(invalid, PlayerFigure.VIDEO_CODEC, figures.videoCodec(), PlayerFiguresCheck::isText);
    check(invalid, PlayerFigure.AUDIO, figures.audio(), PlayerFiguresCheck::isValid);
    check(invalid, PlayerFigure.DISPLAY, figures.display(), PlayerFiguresCheck::isValid);
    check(
        invalid,
        PlayerFigure.AUDIO_OUTPUT,
        figures.audioOutput(),
        output -> isText(output.route()) && output.channels() > 0);
    check(invalid, PlayerFigure.SCREEN, figures.screen(), PlayerFiguresCheck::isValid);
    check(
        invalid,
        PlayerFigure.BROWSER,
        figures.browser(),
        browser -> isText(browser.name()) && isText(browser.version()));
    return List.copyOf(invalid);
  }

  private static <T> void check(
      List<PlayerFigure> invalid, PlayerFigure figure, Optional<T> value, Predicate<T> valid) {
    if (value.isPresent() && !valid.test(value.get())) {
      invalid.add(figure);
    }
  }

  private static void checkDouble(
      List<PlayerFigure> invalid, PlayerFigure figure, OptionalDouble value) {
    if (value.isPresent() && !isNonNegative(value.getAsDouble())) {
      invalid.add(figure);
    }
  }

  private static void checkCount(
      List<PlayerFigure> invalid, PlayerFigure figure, OptionalInt value) {
    if (value.isPresent() && value.getAsInt() < 0) {
      invalid.add(figure);
    }
  }

  private static boolean isValid(PlayerFigures.QualityStep step) {
    return step.number() >= 1
        && step.number() <= step.count()
        && step.height() > 0
        && step.bitrate() > 0;
  }

  private static boolean isValid(PlayerFigures.PlayerAudio audio) {
    return isText(audio.codec()) && isPositiveWhenPresent(audio.channels());
  }

  private static boolean isValid(PlayerFigures.PlayerDisplay display) {
    return display.width().isPresent() == display.height().isPresent()
        && isPositiveWhenPresent(display.width())
        && isPositiveWhenPresent(display.height())
        && display.refreshRate().stream().allMatch(PlayerFiguresCheck::isPositive);
  }

  private static boolean isValid(PlayerFigures.PlayerScreen screen) {
    return screen.width() > 0 && screen.height() > 0 && isPositive(screen.pixelRatio());
  }

  private static boolean isText(String text) {
    return !text.isBlank() && text.codePointCount(0, text.length()) <= MAXIMUM_TEXT_LENGTH;
  }

  private static boolean isPositiveWhenPresent(OptionalInt value) {
    return value.stream().allMatch(number -> number > 0);
  }

  private static boolean isNonNegative(double value) {
    return Double.isFinite(value) && value >= 0;
  }

  private static boolean isPositive(double value) {
    return Double.isFinite(value) && value > 0;
  }
}
