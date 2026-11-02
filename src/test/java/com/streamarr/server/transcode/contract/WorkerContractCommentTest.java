package com.streamarr.server.transcode.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    for (var lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
      if (lines.get(lineNumber - 1).strip().startsWith("//")) {
        blockLength++;
        continue;
      }

      if (blockLength > MAX_COMMENT_LINES) {
        oversized.add("line " + (lineNumber - blockLength) + ": " + blockLength + " lines");
      }

      blockLength = 0;
    }
    return oversized;
  }
}
