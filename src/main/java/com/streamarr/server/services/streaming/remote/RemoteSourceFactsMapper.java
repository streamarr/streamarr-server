package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.Optional;
import java.util.regex.Pattern;

// Maps the source facts that probe version 7 adds: a video stream's sample entry and the file's
// major brand. The contract states each as four printable ASCII characters.
final class RemoteSourceFactsMapper {

  private static final Pattern FOUR_PRINTABLE_CHARACTERS = Pattern.compile("[ -~]{4}");

  private RemoteSourceFactsMapper() {}

  static Optional<String> sampleEntry(ProbeStreamInfo stream) {
    if (!stream.hasSampleEntry()) {
      return Optional.empty();
    }

    if (!"video".equals(stream.getCodecType())) {
      throw contractViolation("a sample entry for a stream that is not video");
    }

    return Optional.of(fourPrintableCharacters(stream.getSampleEntry(), "sample entry"));
  }

  static Optional<String> majorBrand(ProbeContainerInfo container) {
    if (!container.hasMajorBrand()) {
      return Optional.empty();
    }

    return Optional.of(fourPrintableCharacters(container.getMajorBrand(), "major brand"));
  }

  private static String fourPrintableCharacters(String value, String fact) {
    if (!FOUR_PRINTABLE_CHARACTERS.matcher(value).matches()) {
      throw contractViolation("a " + fact + " that is not four printable ASCII characters");
    }

    return value;
  }

  private static ProbeContractViolationException contractViolation(String fact) {
    return new ProbeContractViolationException("Worker probe reported " + fact);
  }
}
