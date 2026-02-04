package com.streamarr.server.services.validation;

import com.streamarr.server.config.LibraryScanProperties;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class IgnoredFileValidator {

  private static final Set<String> DEFAULT_IGNORED_FILENAMES =
      Set.of(".DS_Store", "Thumbs.db", "desktop.ini", "small.jpg", "albumart.jpg");

  private static final Set<String> DEFAULT_IGNORED_EXTENSIONS =
      Set.of(
          "nfo", "txt", "jpg", "jpeg", "png", "gif", "bmp", "part", "tmp", "crdownload", "bts",
          "sync");

  private static final Set<String> DEFAULT_IGNORED_PREFIXES = Set.of("._");

  private final Set<String> ignoredFilenames;
  private final Set<String> ignoredExtensions;
  private final Set<String> ignoredPrefixes;

  public IgnoredFileValidator(LibraryScanProperties properties) {
    this.ignoredFilenames =
        mergeDefaults(DEFAULT_IGNORED_FILENAMES, properties.additionalIgnoredFilenames());
    this.ignoredExtensions =
        mergeDefaults(DEFAULT_IGNORED_EXTENSIONS, properties.additionalIgnoredExtensions());
    this.ignoredPrefixes =
        mergeDefaults(DEFAULT_IGNORED_PREFIXES, properties.additionalIgnoredPrefixes());
  }

  public boolean shouldIgnore(Path path) {
    var filename = path.getFileName().toString();

    if (ignoredFilenames.contains(filename)) {
      return true;
    }

    for (var prefix : ignoredPrefixes) {
      if (filename.startsWith(prefix)) {
        return true;
      }
    }

    var dotIndex = filename.lastIndexOf('.');
    if (dotIndex >= 0) {
      var extension = filename.substring(dotIndex + 1).toLowerCase();
      return ignoredExtensions.contains(extension);
    }

    return false;
  }

  private static Set<String> mergeDefaults(Set<String> defaults, List<String> additional) {
    if (additional.isEmpty()) {
      return defaults;
    }
    var merged = new HashSet<>(defaults);
    merged.addAll(additional);
    return Set.copyOf(merged);
  }
}
