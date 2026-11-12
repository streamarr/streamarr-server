package com.streamarr.server.services.streaming.local;

import com.streamarr.server.exceptions.UnknownFolderLocationException;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * The mounts that a Linux mount table in the format of /proc/self/mountinfo lists, and where a path
 * leads through them.
 */
final class MountTable {

  /** The mount table of this process. Only Linux has it. */
  static final Path OF_THIS_PROCESS = Path.of("/proc/self/mountinfo");

  // A line holds a mount's id, its parent's id, its file system's device number, its root, its
  // mount point and its options, then optional fields, a separator, and three fields about its file
  // system: the type, the source and the options.
  private static final int ID = 0;
  private static final int PARENT_ID = 1;
  private static final int DEVICE = 2;
  private static final int ROOT = 3;
  private static final int MOUNT_POINT = 4;
  private static final int FIRST_OPTIONAL_FIELD = 6;
  private static final int FIELDS_AFTER_THE_SEPARATOR = 3;
  private static final String SEPARATOR = "-";

  private static final Pattern NUMBER = Pattern.compile("\\d+");
  private static final Pattern DEVICE_NUMBER = Pattern.compile("\\d+:\\d+");
  private static final Pattern OPTIONAL_FIELD = Pattern.compile("[a-z_]+(:\\S+)?");

  // The kernel writes a space, tab, newline or backslash in a field as a backslash and three octal
  // digits. It escapes no character from 128 up.
  private static final Pattern ESCAPE = Pattern.compile("\\\\([01][0-7]{2})");
  private static final Pattern BACKSLASH_WITHOUT_ESCAPE = Pattern.compile("\\\\(?![01][0-7]{2})");

  private final List<Mount> mounts;

  private MountTable(List<Mount> mounts) {
    this.mounts = mounts;
  }

  /**
   * Reads the table.
   *
   * @throws IOException if the table cannot be read
   * @throws IllegalArgumentException if a line is not in the kernel's format, or two lines name one
   *     mount
   */
  static MountTable read(Path table) throws IOException {
    try (var lines = Files.lines(table)) {
      var mounts = lines.map(line -> mountOf(table.getFileSystem(), line)).toList();
      if (mounts.stream().map(Mount::id).distinct().count() != mounts.size()) {
        throw new IllegalArgumentException("Two lines of the mount table name one mount");
      }

      return new MountTable(mounts);
    }
  }

  List<Path> mountPoints() {
    return mounts.stream().map(Mount::mountPoint).toList();
  }

  /**
   * Whether the folder lies in the other folder or holds it, under any of their paths. A bind mount
   * or a second mount of a file system gives a folder more than one path, so the table compares
   * where the paths lead: a file system, and a folder in it. A file system that is mounted at or
   * below the other folder counts as part of that folder. Both paths are absolute and lead through
   * no soft link. The table cannot see where an overlay mount, or a second network mount of one
   * remote folder, leads: it shows each of them as a file system of its own.
   *
   * @throws UnknownFolderLocationException if the table does not show where a path leads
   */
  boolean liesInOrHolds(Path folder, Path otherFolder) {
    var location = locationOf(folder);
    var mountedAtOrBelow =
        mounts.stream()
            .filter(mount -> mount.mountPoint().startsWith(otherFolder))
            .map(mount -> new Location(mount.device(), mount.root()));
    return Stream.concat(Stream.of(locationOf(otherFolder)), mountedAtOrBelow)
        .anyMatch(location::liesInOrHolds);
  }

  private Location locationOf(Path path) {
    var mount = mountThatShows(path);
    var folder =
        mount.root().map(root -> root.resolve(mount.mountPoint().relativize(path).toString()));
    return new Location(mount.device(), folder);
  }

  // From the process's root mount down to the path. A mount hides what lies at and below its mount
  // point in the mount that it is mounted on, so of the mounts on one mount that lie on the path,
  // the outermost one shows it. A mount stacked on the point of another mount is mounted on it.
  private Mount mountThatShows(Path path) {
    var mount = rootMount(path);
    var mountsOnIt = mountsOn(mount, path);
    while (!mountsOnIt.isEmpty()) {
      mount = outermost(mountsOnIt, path);
      mountsOnIt = mountsOn(mount, path);
    }

    return mount;
  }

  // The table holds no parent of the process's root mount, or names the root mount as its own
  // parent.
  private Mount rootMount(Path path) {
    var ids = mounts.stream().map(Mount::id).collect(Collectors.toSet());
    return onlyOne(
        mounts.stream()
            .filter(mount -> mount.mountPoint().getParent() == null)
            .filter(mount -> mount.parentId().equals(mount.id()) || !ids.contains(mount.parentId()))
            .toList(),
        path);
  }

  private List<Mount> mountsOn(Mount mount, Path path) {
    return mounts.stream()
        .filter(other -> other.parentId().equals(mount.id()) && !other.id().equals(mount.id()))
        .filter(other -> path.startsWith(other.mountPoint()))
        .toList();
  }

  private static Mount outermost(List<Mount> mountsOnOneMount, Path path) {
    var shortest =
        mountsOnOneMount.stream()
            .mapToInt(mount -> mount.mountPoint().getNameCount())
            .min()
            .orElseThrow();
    return onlyOne(
        mountsOnOneMount.stream()
            .filter(mount -> mount.mountPoint().getNameCount() == shortest)
            .toList(),
        path);
  }

  private static Mount onlyOne(List<Mount> mountsThatCouldShowThePath, Path path) {
    if (mountsThatCouldShowThePath.size() != 1) {
      throw new UnknownFolderLocationException(
          mountsThatCouldShowThePath.size() + " mounts could show " + path + ", not one");
    }

    return mountsThatCouldShowThePath.getFirst();
  }

  private static Mount mountOf(FileSystem fileSystem, String line) {
    var fields = line.split(" ", -1);
    if (!isInTheKernelsFormat(fields)) {
      throw new IllegalArgumentException(
          "A mount table line is not in the kernel's format: " + line);
    }

    return new Mount(
        fields[ID],
        fields[PARENT_ID],
        fields[DEVICE],
        rootFolder(fileSystem, unescaped(fields[ROOT], line)),
        fileSystem.getPath(unescaped(fields[MOUNT_POINT], line)));
  }

  // Only the mount's source can be empty.
  private static boolean isInTheKernelsFormat(String[] fields) {
    var separator =
        IntStream.range(FIRST_OPTIONAL_FIELD, fields.length)
            .filter(field -> fields[field].equals(SEPARATOR))
            .findFirst();
    if (separator.isEmpty()
        || fields.length != separator.getAsInt() + FIELDS_AFTER_THE_SEPARATOR + 1) {
      return false;
    }

    var source = separator.getAsInt() + 2;
    return NUMBER.matcher(fields[ID]).matches()
        && NUMBER.matcher(fields[PARENT_ID]).matches()
        && DEVICE_NUMBER.matcher(fields[DEVICE]).matches()
        && fields[MOUNT_POINT].startsWith("/")
        && IntStream.range(0, fields.length)
            .allMatch(field -> field == source || !fields[field].isEmpty())
        && Arrays.stream(fields, FIRST_OPTIONAL_FIELD, separator.getAsInt())
            .allMatch(field -> OPTIONAL_FIELD.matcher(field).matches());
  }

  private static String unescaped(String field, String line) {
    if (BACKSLASH_WITHOUT_ESCAPE.matcher(field).find()) {
      throw new IllegalArgumentException(
          "A mount table line holds a backslash that the kernel does not write: " + line);
    }

    return ESCAPE
        .matcher(field)
        .replaceAll(
            escape ->
                Matcher.quoteReplacement(Character.toString(Integer.parseInt(escape.group(1), 8))));
  }

  // A mount's root is the folder of its file system that the mount shows. The root of a mount of a
  // namespace file is no folder's path, and the kernel ends the root of a mount whose folder was
  // deleted with //deleted.
  private static Optional<Path> rootFolder(FileSystem fileSystem, String root) {
    if (!root.startsWith("/") || root.contains("//")) {
      return Optional.empty();
    }

    return Optional.of(fileSystem.getPath(root));
  }

  private record Mount(
      String id, String parentId, String device, Optional<Path> root, Path mountPoint) {}

  // A file system, and the folder in it that a path leads to. A mount whose root is no folder's
  // path leads to no known folder.
  private record Location(String device, Optional<Path> folder) {

    boolean liesInOrHolds(Location other) {
      if (!device.equals(other.device)) {
        return false;
      }

      if (folder.isEmpty() || other.folder.isEmpty()) {
        throw new UnknownFolderLocationException(
            "a mount of the file system " + device + " shows no folder's path as its root");
      }

      return folder.get().startsWith(other.folder.get())
          || other.folder.get().startsWith(folder.get());
    }
  }
}
