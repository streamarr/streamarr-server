package com.streamarr.server.services.validation;

import com.streamarr.server.config.LibraryScanProperties;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IgnoredFileValidator {

  private static final Set<String> DEFAULT_IGNORED_FILENAMES =
      Set.of(".ds_store", "thumbs.db", "desktop.ini");

  private static final Set<String> DEFAULT_IGNORED_EXTENSIONS =
      Set.of(
          "nfo",
          "txt",
          "jpg",
          "jpeg",
          "png",
          "gif",
          "bmp",
          "part",
          "tmp",
          "crdownload",
          "bts",
          "sync",
          "srt",
          "sub",
          "ass",
          "ssa",
          "idx");

  private static final Set<String> DEFAULT_IGNORED_PREFIXES = Set.of("._");

  private final Set<String> ignoredFilenames;
  private final Set<String> ignoredExtensions;
  private final Set<String> ignoredPrefixes;

  public IgnoredFileValidator(LibraryScanProperties properties) {
    this.ignoredFilenames =
        mergeDefaults(
            DEFAULT_IGNORED_FILENAMES,
            properties.additionalIgnoredFilenames().stream().map(String::toLowerCase).toList());
    this.ignoredExtensions =
        mergeDefaults(DEFAULT_IGNORED_EXTENSIONS, properties.additionalIgnoredExtensions());
    this.ignoredPrefixes =
        mergeDefaults(DEFAULT_IGNORED_PREFIXES, properties.additionalIgnoredPrefixes());
  }

  public boolean shouldIgnore(Path path) {
    var filename = path.getFileName().toString();

    if (ignoredFilenames.contains(filename.toLowerCase())) {
      log.debug("Ignoring file with blocked filename: {}", path);
      return true;
    }

    if (hasIgnoredPrefix(path, filename)) {
      return true;
    }

    var dotIndex = filename.lastIndexOf('.');
    if (dotIndex < 0) {
      return false;
    }

    var extension = filename.substring(dotIndex + 1).toLowerCase();
    if (ignoredExtensions.contains(extension)) {
      log.debug("Ignoring file with blocked extension '{}': {}", extension, path);
      return true;
    }

    return false;
  }

  private boolean hasIgnoredPrefix(Path path, String filename) {
    for (var prefix : ignoredPrefixes) {
      if (filename.startsWith(prefix)) {
        log.debug("Ignoring file with blocked prefix '{}': {}", prefix, path);
        return true;
      }
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
