package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.streamarr.server.exceptions.UnknownFolderLocationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Mount Table Tests")
class MountTableTest {

  private static final String ROOT_FILE_SYSTEM =
      "22 1 8:1 / / rw,relatime shared:1 - ext4 /dev/sda1 rw\n";

  @TempDir Path machine;

  @Test
  @DisplayName("Should read each line's mount point when the table lists several mounts")
  void shouldReadEachLinesMountPointWhenTheTableListsSeveralMounts() throws IOException {
    var mountTable =
        tableOf(
            """
            22 1 8:1 / / rw,relatime shared:1 - ext4 /dev/sda1 rw
            36 22 0:32 / /tmp rw,nosuid,nodev shared:5 - tmpfs tmpfs rw
            41 36 8:1 /media/movies /tmp/segments/library rw,relatime shared:1 - ext4 /dev/sda1 rw
            """);

    var mountPoints = MountTable.read(mountTable).mountPoints();

    assertThat(mountPoints)
        .containsExactly(Path.of("/"), Path.of("/tmp"), Path.of("/tmp/segments/library"));
  }

  // The kernel escapes these four characters, so that a space still separates the fields.
  @Test
  @DisplayName(
      "Should read a space, tab, newline and backslash in a mount point when the kernel escaped"
          + " them")
  void shouldReadASpaceTabNewlineAndBackslashInAMountPointWhenTheKernelEscapedThem()
      throws IOException {
    var mountTable =
        tableOf("41 22 8:1 / /media/my\\040movies\\011a\\012b\\134c rw - ext4 /dev/sda1 rw\n");

    var mountPoints = MountTable.read(mountTable).mountPoints();

    assertThat(mountPoints).containsExactly(Path.of("/media/my movies\ta\nb\\c"));
  }

  // Optional fields name the mount's peer groups. A mount can have none, or several. A mount
  // source can be empty.
  @Test
  @DisplayName(
      "Should read each line when the lines have no optional fields or several, or no mount source")
  void shouldReadEachLineWhenTheLinesHaveNoOptionalFieldsOrSeveralOrNoMountSource()
      throws IOException {
    var mountTable =
        tableOf(
            """
            22 1 8:1 / / rw,relatime - ext4 /dev/sda1 rw
            36 22 0:32 / /tmp rw,nosuid,nodev shared:5 - tmpfs tmpfs rw
            41 36 0:33 / /tmp/a rw shared:6 master:2 propagate_from:3 unbindable - tmpfs tmpfs rw
            42 36 0:34 / /tmp/b rw - tmpfs  rw
            """);

    var mountPoints = MountTable.read(mountTable).mountPoints();

    assertThat(mountPoints)
        .containsExactly(Path.of("/"), Path.of("/tmp"), Path.of("/tmp/a"), Path.of("/tmp/b"));
  }

  @Test
  @DisplayName("Should reject the table when a line has no mount point")
  void shouldRejectTheTableWhenALineHasNoMountPoint() throws IOException {
    var mountTable = tableOf("41 22 8:1 /\n");

    assertThatIllegalArgumentException().isThrownBy(() -> MountTable.read(mountTable));
  }

  static Stream<Named<String>> linesNotInTheKernelsFormat() {
    return Stream.of(
        Named.of("an empty line", ""),
        Named.of("a mount id that is no number", "x 22 8:1 / /srv rw - ext4 /dev/sda1 rw"),
        Named.of("a parent id that is no number", "41 y 8:1 / /srv rw - ext4 /dev/sda1 rw"),
        Named.of("a device that is no device number", "41 22 sda1 / /srv rw - ext4 /dev/sda1 rw"),
        Named.of("a mount point that is not absolute", "41 22 8:1 / srv rw - ext4 /dev/sda1 rw"),
        Named.of("an empty field", "41 22 8:1 / /srv  rw - ext4 /dev/sda1 rw"),
        Named.of("an empty file system type", "41 22 8:1 / /srv rw -  /dev/sda1 rw"),
        Named.of("no separator", "41 22 8:1 / /srv rw shared:1 ext4 /dev/sda1 rw"),
        Named.of("an optional field that is no tag", "41 22 8:1 / /srv rw =1 - ext4 /dev/sda1 rw"),
        Named.of("a field missing after the separator", "41 22 8:1 / /srv rw - ext4 rw"),
        Named.of("a field more after the separator", "41 22 8:1 / /srv rw - ext4 /dev/sda1 rw rw"),
        Named.of("a backslash that starts no escape", "41 22 8:1 / /srv\\x rw - ext4 /dev/sda1 rw"),
        Named.of(
            "an escape that the kernel does not write",
            "41 22 8:1 /\\303\\251 /srv rw - ext4 /dev/sda1 rw"));
  }

  @ParameterizedTest
  @MethodSource("linesNotInTheKernelsFormat")
  @DisplayName("Should reject the table when a line is not in the kernel's format")
  void shouldRejectTheTableWhenALineIsNotInTheKernelsFormat(String line) throws IOException {
    var mountTable = tableOf(ROOT_FILE_SYSTEM + line + "\n");

    assertThatIllegalArgumentException().isThrownBy(() -> MountTable.read(mountTable));
  }

  @Test
  @DisplayName("Should reject the table when two lines name one mount")
  void shouldRejectTheTableWhenTwoLinesNameOneMount() throws IOException {
    var mountTable =
        tableOf(ROOT_FILE_SYSTEM + "22 1 0:41 / /run rw,nosuid shared:2 - tmpfs tmpfs rw\n");

    assertThatIllegalArgumentException().isThrownBy(() -> MountTable.read(mountTable));
  }

  @Test
  @DisplayName("Should place a folder in a library when the library is itself a bind mount")
  void shouldPlaceAFolderInALibraryWhenTheLibraryIsItselfABindMount() throws IOException {
    var mounts =
        MountTable.read(
            tableOf(
                ROOT_FILE_SYSTEM
                    + "51 22 8:1 /data/movies /media/movies rw - ext4 /dev/sda1 rw\n"));

    var liesInTheLibrary =
        mounts.liesInOrHolds(Path.of("/data/movies/segments"), Path.of("/media/movies"));

    assertThat(liesInTheLibrary).isTrue();
  }

  static Stream<Arguments> mountsThatLeadAFolderIntoTheLibrary() {
    return Stream.of(
        argumentSet(
            "a file system that is mounted in the library leads to the folder",
            """
            60 22 0:61 / /srv/media/nas rw,relatime shared:9 - nfs4 nas:/export rw
            61 22 0:61 /cache /segments rw,relatime shared:9 - nfs4 nas:/export rw
            """,
            Path.of("/segments")),
        // A mount on the point of the mount below it is that mount's child.
        argumentSet(
            "the top one of two mounts that are stacked on the folder leads to it",
            """
            41 22 0:41 / /segments rw,nosuid shared:2 - tmpfs tmpfs rw
            42 41 8:1 /srv/media/cache /segments rw - ext4 /dev/sda1 rw
            """,
            Path.of("/segments")),
        // The tmpfs was mounted on /srv/segments/cache first, then the bind mount on /srv/segments
        // hid it. Both are mounts of the root file system's mount.
        argumentSet(
            "the mount on a folder above the folder hides an earlier mount on the folder",
            """
            41 22 0:41 / /srv/segments/cache rw,nosuid shared:2 - tmpfs tmpfs rw
            42 22 8:1 /srv/media /srv/segments rw - ext4 /dev/sda1 rw
            """,
            Path.of("/srv/segments/cache")));
  }

  @ParameterizedTest
  @MethodSource("mountsThatLeadAFolderIntoTheLibrary")
  @DisplayName(
      "Should place a folder in a library when the mounts on the folder's path lead into the"
          + " library")
  void shouldPlaceAFolderInALibraryWhenTheMountsOnTheFoldersPathLeadIntoTheLibrary(
      String lines, Path folder) throws IOException {
    var mounts = MountTable.read(tableOf(ROOT_FILE_SYSTEM + lines));

    var liesInTheLibrary = mounts.liesInOrHolds(folder, Path.of("/srv/media"));

    assertThat(liesInTheLibrary).isTrue();
  }

  // Path names compare whole: /srv/my is no folder above /srv/my media.
  @Test
  @DisplayName("Should read an escaped space in a mount's root when it places a folder")
  void shouldReadAnEscapedSpaceInAMountsRootWhenItPlacesAFolder() throws IOException {
    var mounts =
        MountTable.read(
            tableOf(
                ROOT_FILE_SYSTEM
                    + "41 22 8:1 /srv/my\\040media/cache /seg\\040ments rw - ext4 /dev/sda1 rw\n"));

    assertThat(mounts.liesInOrHolds(Path.of("/seg ments"), Path.of("/srv/my media"))).isTrue();
    assertThat(mounts.liesInOrHolds(Path.of("/seg ments"), Path.of("/srv/my"))).isFalse();
  }

  @Test
  @DisplayName(
      "Should place folders apart when the table holds the mount of a namespace on another file"
          + " system")
  void shouldPlaceFoldersApartWhenTheTableHoldsTheMountOfANamespaceOnAnotherFileSystem()
      throws IOException {
    var mounts =
        MountTable.read(
            tableOf(
                ROOT_FILE_SYSTEM
                    + "47 26 0:4 net:[4026532275] /run/netns/vpn rw shared:3 - nsfs nsfs rw\n"));

    var liesInTheLibrary = mounts.liesInOrHolds(Path.of("/segments"), Path.of("/srv/media"));

    assertThat(liesInTheLibrary).isFalse();
  }

  // Neither mount hides the other, so the table does not show which one the folder's path leads
  // through.
  @Test
  @DisplayName("Should not place a folder when two mounts of one mount share its mount point")
  void shouldNotPlaceAFolderWhenTwoMountsOfOneMountShareItsMountPoint() throws IOException {
    var mounts =
        MountTable.read(
            tableOf(
                ROOT_FILE_SYSTEM
                    + """
                    41 22 0:41 / /segments rw,nosuid shared:2 - tmpfs tmpfs rw
                    42 22 8:1 /srv/media/cache /segments rw - ext4 /dev/sda1 rw
                    """));

    assertThatThrownBy(() -> mounts.liesInOrHolds(Path.of("/segments"), Path.of("/srv/media")))
        .isInstanceOf(UnknownFolderLocationException.class);
  }

  // The kernel ends the root of a mount whose folder was deleted with //deleted, and gives a
  // namespace file's name as the root of its mount.
  static Stream<Named<String>> mountsWhoseRootIsNoFoldersPath() {
    return Stream.of(
        Named.of(
            "the folder's mount shows a deleted folder",
            "41 22 8:1 /srv/media/cache//deleted /segments rw - ext4 /dev/sda1 rw"),
        Named.of(
            "the folder's mount shows a namespace file",
            "41 22 8:1 net:[4026532275] /segments rw - ext4 /dev/sda1 rw"),
        Named.of(
            "the library's mount shows a deleted folder",
            "41 22 8:1 /data/media//deleted /srv/media rw - ext4 /dev/sda1 rw"));
  }

  @ParameterizedTest
  @MethodSource("mountsWhoseRootIsNoFoldersPath")
  @DisplayName(
      "Should not place a folder when a mount on the library's file system shows no folder's path"
          + " as its root")
  void shouldNotPlaceAFolderWhenAMountOnTheLibrarysFileSystemShowsNoFoldersPathAsItsRoot(
      String mount) throws IOException {
    var mounts = MountTable.read(tableOf(ROOT_FILE_SYSTEM + mount + "\n"));

    assertThatThrownBy(() -> mounts.liesInOrHolds(Path.of("/segments"), Path.of("/srv/media")))
        .isInstanceOf(UnknownFolderLocationException.class);
  }

  // A process whose root is its mount namespace's first mount, which never moved, sees that mount
  // as its own parent. A walk that took the mount for one of its own mounts would never end.
  @Test
  @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  @DisplayName("Should place a folder when the root mount names itself as its parent")
  void shouldPlaceAFolderWhenTheRootMountNamesItselfAsItsParent() throws IOException {
    var mounts =
        MountTable.read(
            tableOf(
                """
                1 1 0:2 / / rw - rootfs rootfs rw
                41 1 0:2 /srv/media/cache /segments rw - rootfs rootfs rw
                """));

    var liesInTheLibrary = mounts.liesInOrHolds(Path.of("/segments"), Path.of("/srv/media"));

    assertThat(liesInTheLibrary).isTrue();
  }

  private Path tableOf(String lines) throws IOException {
    return Files.writeString(machine.resolve("mountinfo"), lines);
  }
}
