package com.streamarr.server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("UnitTest")
@DisplayName("Presentation DTO Naming Tests")
@AnalyzeClasses(packages = "com.streamarr.server")
class PresentationDtoNamingTest {

  @ArchTest
  static final ArchRule presentationDtosMustDescribeDetailsRatherThanViews =
      noClasses()
          .should()
          .haveSimpleNameEndingWith("View")
          .as("Presentation DTOs describe details and must not use the misleading View suffix");
}
