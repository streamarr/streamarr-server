package com.streamarr.server.exceptions;

import com.streamarr.server.domain.LibraryFolderProblem;
import lombok.Getter;

@Getter
public class LibraryFolderCheckFailedException extends LibraryScanFailedException {

  private final LibraryFolderProblem problem;

  public LibraryFolderCheckFailedException(String libraryName, LibraryFolderProblem problem) {
    super(describe(libraryName, problem));
    this.problem = problem;
  }

  private static String describe(String libraryName, LibraryFolderProblem problem) {
    return switch (problem) {
      case MISSING -> "The folder of library %s does not exist.".formatted(libraryName);
      case UNREADABLE -> "Streamarr cannot read the folder of library %s.".formatted(libraryName);
      case EMPTY ->
          "The folder of library %s is empty, but Streamarr still records files for the library."
              .formatted(libraryName);
    };
  }
}
