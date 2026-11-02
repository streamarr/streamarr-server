package com.streamarr.server.transcode.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker Contract Comment Tests")
class WorkerContractCommentTest {

  private static final Path CONTRACT =
      Path.of("src/main/protobuf/streamarr/transcode/v1/worker_session.proto");

  // A field comment states meaning, units, the absent value and one rule, then links the ADR
  // that holds the behavior.
  private static final int MAX_COMMENT_LINES = 6;

  @Test
  @DisplayName("Should keep every worker contract comment to a field contract when declared")
  void shouldKeepEveryWorkerContractCommentToAFieldContractWhenDeclared() throws IOException {
    assertThat(commentBlocksLongerThanTheLimit(Files.readAllLines(CONTRACT))).isEmpty();
  }

  private static List<String> commentBlocksLongerThanTheLimit(List<String> lines) {
    var oversized = new ArrayList<String>();
    var blockLength = 0;
    // The empty line after the last line closes a comment block that ends the file.
    var linesWithEnd = Stream.concat(lines.stream(), Stream.of("")).toList();
    for (var index = 0; index < linesWithEnd.size(); index++) {
      if (linesWithEnd.get(index).strip().startsWith("//")) {
        blockLength++;
        continue;
      }

      if (blockLength > MAX_COMMENT_LINES) {
        oversized.add("line " + (index - blockLength + 1) + ": " + blockLength + " lines");
      }

      blockLength = 0;
    }

    return oversized;
  }
}
