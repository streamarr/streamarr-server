package com.streamarr.server.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record BrunoRequestDocument(
    String method,
    String url,
    String bodyMode,
    String authMode,
    Map<String, String> headers,
    JsonNode jsonBody,
    String preRequestScript) {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final Set<String> HTTP_METHODS =
      Set.of("delete", "get", "head", "options", "patch", "post", "put");
  private static final Pattern SECTION_START =
      Pattern.compile("(?m)^([a-zA-Z][\\w:-]*)\\s*(\\{)\\s*$");
  private static final Pattern COOKIE_READ =
      Pattern.compile("bru\\.cookies\\.get\\(\\s*([\"'])([^\"']+)\\1\\s*\\)");
  private static final Pattern HEADER_WRITE =
      Pattern.compile(
          "req\\.setHeader\\(\\s*([\"'])([^\"']+)\\1\\s*,\\s*([a-zA-Z_$][\\w$]*)\\s*\\)");

  public BrunoRequestDocument {
    headers = Map.copyOf(headers);
  }

  public static BrunoRequestDocument parse(Path path) throws IOException {
    var sections = sectionsOf(Files.readString(path));
    var method =
        HTTP_METHODS.stream()
            .filter(sections::containsKey)
            .findFirst()
            .orElseThrow(() -> malformed(path, "HTTP method section missing"));
    var request = fieldsOf(requiredSection(sections, method, path));

    return new BrunoRequestDocument(
        method.toUpperCase(Locale.ROOT),
        requiredField(request, "url", path),
        requiredField(request, "body", path),
        requiredField(request, "auth", path),
        fieldsOf(requiredSection(sections, "headers", path)),
        OBJECT_MAPPER.readTree(requiredSection(sections, "body:json", path)),
        requiredSection(sections, "script:pre-request", path).strip());
  }

  public List<String> preRequestCookieNames() {
    return COOKIE_READ
        .matcher(withoutComments(preRequestScript))
        .results()
        .map(match -> match.group(2))
        .toList();
  }

  public Map<String, String> preRequestHeaders() {
    var headers = new LinkedHashMap<String, String>();
    HEADER_WRITE
        .matcher(withoutComments(preRequestScript))
        .results()
        .forEach(match -> headers.put(match.group(2), match.group(3)));
    return Map.copyOf(headers);
  }

  private static Map<String, String> sectionsOf(String document) {
    var sections = new LinkedHashMap<String, String>();
    var matcher = SECTION_START.matcher(document);
    var searchFrom = 0;

    while (matcher.find(searchFrom)) {
      var name = matcher.group(1);
      var openingBrace = matcher.start(2);
      var closingBrace = matchingBrace(document, openingBrace);
      var previous = sections.put(name, document.substring(openingBrace + 1, closingBrace).strip());
      if (previous != null) {
        throw new IllegalArgumentException("Duplicate Bruno section: " + name);
      }
      searchFrom = closingBrace + 1;
    }

    return sections;
  }

  private static int matchingBrace(String document, int openingBrace) {
    var depth = 0;
    var quote = '\0';
    var escaped = false;

    for (var index = openingBrace; index < document.length(); index++) {
      var character = document.charAt(index);
      if (quote != '\0') {
        if (escaped) {
          escaped = false;
          continue;
        }
        if (character == '\\') {
          escaped = true;
          continue;
        }
        if (character == quote) {
          quote = '\0';
        }
        continue;
      }
      if (character == '\'' || character == '"' || character == '`') {
        quote = character;
        continue;
      }
      if (character == '{') {
        depth++;
        continue;
      }
      if (character != '}') {
        continue;
      }
      depth--;
      if (depth == 0) {
        return index;
      }
    }

    throw new IllegalArgumentException("Unclosed Bruno section");
  }

  private static Map<String, String> fieldsOf(String section) {
    var fields = new LinkedHashMap<String, String>();
    section
        .lines()
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .forEach(
            line -> {
              var separator = line.indexOf(':');
              if (separator < 1) {
                throw new IllegalArgumentException("Malformed Bruno field: " + line);
              }
              fields.put(line.substring(0, separator), line.substring(separator + 1).strip());
            });
    return fields;
  }

  private static String withoutComments(String script) {
    var executable = new StringBuilder();
    var quote = '\0';
    var escaped = false;
    var lineComment = false;
    var blockComment = false;

    for (var index = 0; index < script.length(); index++) {
      var character = script.charAt(index);
      var next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';
      if (lineComment) {
        if (character == '\n') {
          lineComment = false;
          executable.append(character);
        }
        continue;
      }
      if (blockComment) {
        if (character == '*' && next == '/') {
          blockComment = false;
          index++;
        }
        continue;
      }
      if (quote != '\0') {
        executable.append(character);
        if (escaped) {
          escaped = false;
          continue;
        }
        if (character == '\\') {
          escaped = true;
          continue;
        }
        if (character == quote) {
          quote = '\0';
        }
        continue;
      }
      if (character == '\'' || character == '"' || character == '`') {
        quote = character;
        executable.append(character);
        continue;
      }
      if (character == '/' && next == '/') {
        lineComment = true;
        index++;
        continue;
      }
      if (character == '/' && next == '*') {
        blockComment = true;
        index++;
        continue;
      }
      executable.append(character);
    }

    return executable.toString();
  }

  private static String requiredSection(Map<String, String> sections, String name, Path path) {
    var section = sections.get(name);
    if (section == null) {
      throw malformed(path, "section missing: " + name);
    }
    return section;
  }

  private static String requiredField(Map<String, String> fields, String name, Path path) {
    var field = fields.get(name);
    if (field == null) {
      throw malformed(path, "request field missing: " + name);
    }
    return field;
  }

  private static IllegalArgumentException malformed(Path path, String reason) {
    return new IllegalArgumentException("Malformed Bruno request " + path + ": " + reason);
  }
}
