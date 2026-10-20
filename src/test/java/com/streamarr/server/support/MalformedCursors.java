package com.streamarr.server.support;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cursors a client could send instead of a genuine one: the genuine payload with each field, at
 * every depth, removed or replaced by every JSON shape; the payload cut short at every length;
 * other JSON documents; and seeded random bytes. A cursor decoder must decode each of them or
 * reject it as an invalid cursor.
 */
public final class MalformedCursors {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final List<JsonNode> SHAPES =
      List.of(
          NODES.nullNode(),
          NODES.objectNode(),
          NODES.arrayNode(),
          NODES.booleanNode(true),
          NODES.numberNode(0),
          NODES.numberNode(-1),
          NODES.numberNode(1.5),
          NODES.numberNode(new BigInteger("99999999999999999999")),
          NODES.stringNode(""),
          NODES.stringNode("not a value"),
          NODES.stringNode("9999-99-99T99:99:99Z"));
  private static final int RANDOM_CURSORS = 200;

  private MalformedCursors() {}

  public static Stream<String> of(String genuineCursor) {
    var payload = Base64.getUrlDecoder().decode(genuineCursor);
    var tree = JSON.readTree(payload);
    return Stream.of(fieldMutations(tree), truncations(payload), otherDocuments(), randomBytes())
        .flatMap(stream -> stream)
        .map(MalformedCursors::encode);
  }

  private static Stream<byte[]> fieldMutations(JsonNode tree) {
    var mutations = new ArrayList<byte[]>();
    mutate(tree, tree, mutations);
    return mutations.stream();
  }

  private static void mutate(JsonNode root, JsonNode node, List<byte[]> mutations) {
    if (node instanceof ObjectNode object) {
      for (var name : List.copyOf(object.propertyNames())) {
        var original = object.get(name);
        mutations.add(
            withChange(root, () -> object.remove(name), () -> object.set(name, original)));
        for (var shape : SHAPES) {
          mutations.add(
              withChange(root, () -> object.set(name, shape), () -> object.set(name, original)));
        }

        mutate(root, original, mutations);
      }
    }

    if (node instanceof ArrayNode array) {
      for (var index = 0; index < array.size(); index++) {
        var original = array.get(index);
        var at = index;
        for (var shape : SHAPES) {
          mutations.add(
              withChange(root, () -> array.set(at, shape), () -> array.set(at, original)));
        }

        mutate(root, original, mutations);
      }
    }
  }

  private static byte[] withChange(JsonNode root, Runnable change, Runnable undo) {
    change.run();
    var bytes = JSON.writeValueAsBytes(root);
    undo.run();
    return bytes;
  }

  private static Stream<byte[]> truncations(byte[] payload) {
    return IntStream.range(0, payload.length).mapToObj(length -> Arrays.copyOf(payload, length));
  }

  private static Stream<byte[]> otherDocuments() {
    return Stream.of("null", "[]", "1", "\"cursor\"", "{}", "{\"a\":", "\u0000")
        .map(text -> text.getBytes(StandardCharsets.UTF_8));
  }

  private static Stream<byte[]> randomBytes() {
    var random = new Random(20261020L);
    return IntStream.range(0, RANDOM_CURSORS)
        .mapToObj(
            _ -> {
              var bytes = new byte[random.nextInt(1, 96)];
              random.nextBytes(bytes);
              return bytes;
            });
  }

  private static String encode(byte[] payload) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
  }
}
