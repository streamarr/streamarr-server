package com.streamarr.server.jooq;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.jooq.generated.Public;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

// A class initializes once per class loader, so each case loads the generated code into a fresh
// loader. The load hook holds the schema thread inside the schema's static initializer until the
// table thread enters the table's static initializer or finishes.
@Tag("UnitTest")
@DisplayName("Generated schema initialization tests")
class GeneratedSchemaInitializationTest {

  private static final String SCHEMA_CLASS = Public.class.getName();
  private static final String GENERATED_PACKAGE = Public.class.getPackageName() + ".";
  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final Executor DAEMON_THREADS = task -> Thread.ofPlatform().daemon().start(task);

  static Stream<String> tableClassNames() throws Exception {
    try (var loader = new GeneratedCodeLoader(_ -> {})) {
      var schemaClass = Class.forName(SCHEMA_CLASS, true, loader);
      var schema = schemaClass.getField("PUBLIC").get(null);
      var tables = (List<?>) schemaClass.getMethod("getTables").invoke(schema);
      return tables.stream().map(table -> table.getClass().getName()).toList().stream();
    }
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("tableClassNames")
  @DisplayName("Should initialize the schema and a table when two threads start them at once")
  void shouldInitializeSchemaAndTableWhenTwoThreadsStartThemAtOnce(String tableClassName)
      throws Exception {
    var schemaInitializing = new CountDownLatch(1);
    var tableInitializing = new CountDownLatch(1);
    var schemaThreadHeld = new AtomicBoolean();

    Consumer<String> holdSchemaUntilTableStarts =
        _ -> {
          if (isInsideStaticInitializerOf(tableClassName)) {
            tableInitializing.countDown();
          }

          if (isInsideStaticInitializerOf(SCHEMA_CLASS)
              && schemaThreadHeld.compareAndSet(false, true)) {
            schemaInitializing.countDown();
            awaitWithinBound(tableInitializing);
          }
        };

    try (var loader = new GeneratedCodeLoader(holdSchemaUntilTableStarts)) {
      var schemaInitialized = initializeOnNewThread(SCHEMA_CLASS, loader);
      assertThat(schemaInitializing.await(BOUND.toSeconds(), TimeUnit.SECONDS))
          .as("the schema thread entered the schema's static initializer")
          .isTrue();

      var tableInitialized = initializeOnNewThread(tableClassName, loader);
      tableInitialized.whenComplete((_, _) -> tableInitializing.countDown());

      assertThat(CompletableFuture.allOf(schemaInitialized, tableInitialized))
          .succeedsWithin(BOUND);
    }
  }

  private static CompletableFuture<Void> initializeOnNewThread(
      String className, ClassLoader loader) {
    return CompletableFuture.runAsync(() -> initialize(className, loader), DAEMON_THREADS);
  }

  private static void initialize(String className, ClassLoader loader) {
    try {
      Class.forName(className, true, loader);
    } catch (ClassNotFoundException e) {
      throw new AssertionError(e);
    }
  }

  private static boolean isInsideStaticInitializerOf(String className) {
    return StackWalker.getInstance()
        .walk(
            frames ->
                frames.anyMatch(
                    frame ->
                        frame.getMethodName().equals("<clinit>")
                            && frame.getClassName().equals(className)));
  }

  private static void awaitWithinBound(CountDownLatch latch) {
    try {
      latch.await(BOUND.toSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  // Defines the generated classes itself and delegates every other class to the test's loader.
  // The JVM calls loadClass for each class a generated class resolves for the first time, so the
  // listener runs inside the static initializer that triggered the resolution.
  private static final class GeneratedCodeLoader extends URLClassLoader {

    static {
      registerAsParallelCapable();
    }

    private final Consumer<String> listener;

    private GeneratedCodeLoader(Consumer<String> listener) {
      super(
          new URL[] {generatedCodeLocation()},
          GeneratedSchemaInitializationTest.class.getClassLoader());
      this.listener = listener;
    }

    private static URL generatedCodeLocation() {
      return Public.class.getProtectionDomain().getCodeSource().getLocation();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      listener.accept(name);
      if (!name.startsWith(GENERATED_PACKAGE)) {
        return super.loadClass(name, resolve);
      }

      synchronized (getClassLoadingLock(name)) {
        var loaded = findLoadedClass(name);
        if (loaded == null) {
          loaded = findClass(name);
        }

        if (resolve) {
          resolveClass(loaded);
        }

        return loaded;
      }
    }
  }
}
