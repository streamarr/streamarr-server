package com.streamarr.server.services.streaming.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The mount points that a Linux mount table in the format of /proc/self/mountinfo lists. */
final class MountPoints {

  /** The mount table of this process. Only Linux has it. */
  static final Path OF_THIS_PROCESS = Path.of("/proc/self/mountinfo");

  // The kernel writes a space, tab, newline or backslash in a mount point as a backslash and three
  // octal digits.
  private static final Pattern ESCAPE = Pattern.compile("\\\\([0-7]{3})");

  // The fifth field of each line.
  private static final int MOUNT_POINT_FIELD = 4;

  private MountPoints() {}

  /**
   * Reads the mount points in the table.
   *
   * @throws IOException if the table cannot be read
   * @throws IllegalArgumentException if a line has no mount point
   */
  static List<Path> in(Path mountTable) throws IOException {
    try (var lines = Files.lines(mountTable)) {
      return lines.map(line -> mountTable.getFileSystem().getPath(mountPoint(line))).toList();
    }
  }

  private static String mountPoint(String line) {
    var fields = line.split(" ");
    if (fields.length <= MOUNT_POINT_FIELD) {
      throw new IllegalArgumentException("A mount table line has no mount point: " + line);
    }

    return ESCAPE
        .matcher(fields[MOUNT_POINT_FIELD])
        .replaceAll(
            escape ->
                Matcher.quoteReplacement(Character.toString(Integer.parseInt(escape.group(1), 8))));
  }
}
