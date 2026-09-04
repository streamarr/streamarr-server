package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

@Tag("UnitTest")
@DisplayName("Procfile Tests")
class ProcfileTest {

  private static final String NATIVE_ACCESS = "--enable-native-access=ALL-UNNAMED";

  @Test
  // The Procfile is a literal external contract: launcher class names appear as text.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @DisplayName("Should ship separate server and transcode worker process types")
  void shouldShipSeparateServerAndTranscodeWorkerProcessTypes() throws IOException {
    var processes = processes();

    assertThat(processes)
        .containsOnlyKeys("web", "worker")
        .containsEntry(
            "worker",
            "java -Dloader.main=com.streamarr.transcode.worker.TranscodeWorkerApplication"
                + " org.springframework.boot.loader.launch.PropertiesLauncher")
        .hasEntrySatisfying(
            "web",
            web -> assertThat(web).endsWith("org.springframework.boot.loader.launch.JarLauncher"));
  }

  @Test
  @DisplayName("Should enable native access for the web process when it loads the Cedar engine")
  void shouldEnableNativeAccessForWebProcessWhenItLoadsCedarEngine() throws IOException {
    assertThat(processes().get("web")).startsWith("java " + NATIVE_ACCESS + " ");
  }

  @Test
  @DisplayName("Should enable native access when the build starts any JVM")
  void shouldEnableNativeAccessWhenBuildStartsAnyJvm()
      throws IOException, ParserConfigurationException, SAXException, XPathExpressionException {
    var pom = pom();

    assertThat(pomValue(pom, "native.access.args")).isEqualTo(NATIVE_ACCESS);
    assertThat(pluginValue(pom, "maven-surefire-plugin", "argLine"))
        .isEqualTo("${surefire.jacoco.args} ${native.access.args}");
    assertThat(pluginValue(pom, "maven-failsafe-plugin", "argLine"))
        .isEqualTo("${failsafe.jacoco.args} ${native.access.args}");
    assertThat(pluginValue(pom, "spring-boot-maven-plugin", "jvmArguments"))
        .isEqualTo("${native.access.args}");
  }

  private static Map<String, String> processes() throws IOException {
    return Files.readAllLines(Path.of("Procfile")).stream()
        .filter(line -> !line.isBlank())
        .map(line -> line.split(": ", 2))
        .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1]));
  }

  private static Document pom() throws IOException, ParserConfigurationException, SAXException {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setNamespaceAware(true);
    return factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
  }

  private static String pomValue(Document pom, String property) throws XPathExpressionException {
    return xpath(
        pom,
        "/*[local-name()='project']/*[local-name()='properties']/*[local-name()='"
            + property
            + "']");
  }

  private static String pluginValue(Document pom, String artifactId, String setting)
      throws XPathExpressionException {
    return xpath(
        pom,
        "/*[local-name()='project']/*[local-name()='build']/*[local-name()='plugins']"
            + "/*[local-name()='plugin'][*[local-name()='artifactId']='"
            + artifactId
            + "']/*[local-name()='configuration']/*[local-name()='"
            + setting
            + "']");
  }

  private static String xpath(Document pom, String expression) throws XPathExpressionException {
    return XPathFactory.newInstance().newXPath().evaluate(expression, pom).strip();
  }
}
