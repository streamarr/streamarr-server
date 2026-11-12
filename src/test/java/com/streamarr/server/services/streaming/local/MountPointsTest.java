package com.streamarr.server.services.streaming.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Mount Points Tests")
class MountPointsTest {

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

    var mountPoints = MountPoints.in(mountTable);

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

    var mountPoints = MountPoints.in(mountTable);

    assertThat(mountPoints).containsExactly(Path.of("/media/my movies\ta\nb\\c"));
  }

  @Test
  @DisplayName("Should reject the table when a line has no mount point")
  void shouldRejectTheTableWhenALineHasNoMountPoint() throws IOException {
    var mountTable = tableOf("41 22 8:1 /\n");

    assertThatIllegalArgumentException().isThrownBy(() -> MountPoints.in(mountTable));
  }

  private Path tableOf(String lines) throws IOException {
    return Files.writeString(machine.resolve("mountinfo"), lines);
  }
}
