package com.streamarr.server.architecturefixture;

import com.streamarr.transcode.engine.architecturefixture.EngineImplementationFixture;
import com.streamarr.transcode.probe.architecturefixture.ProbeImplementationFixture;
import com.streamarr.transcode.worker.architecturefixture.WorkerImplementationFixture;
import java.io.File;
import java.io.IOException;

public final class WorkerBoundaryFixtures {

  private WorkerBoundaryFixtures() {}

  public static final class ProcessBuilderLaunch {

    public Process launch() throws IOException {
      return new ProcessBuilder("fixture-command-never-executed").start();
    }
  }

  public static final class RuntimeArrayCommand {

    public Process launch() throws IOException {
      return Runtime.getRuntime().exec(new String[] {"fixture-command-never-executed"});
    }
  }

  public record ProcessBuilderReference(ProcessBuilder processBuilder) {}

  public static final class RuntimeArrayEnvironment {

    public Process launch() throws IOException {
      return Runtime.getRuntime()
          .exec(new String[] {"fixture-command-never-executed"}, new String[0]);
    }
  }

  public static final class RuntimeArrayDirectory {

    public Process launch() throws IOException {
      return Runtime.getRuntime()
          .exec(new String[] {"fixture-command-never-executed"}, new String[0], new File("."));
    }
  }

  public static final class RuntimeStringCommand {

    // The bytecode fixture deliberately covers the deprecated String command overload.
    @SuppressWarnings("deprecation")
    public Process launch() throws IOException {
      return Runtime.getRuntime().exec("fixture-command-never-executed");
    }
  }

  public static final class RuntimeStringEnvironment {

    @SuppressWarnings("deprecation")
    public Process launch() throws IOException {
      return Runtime.getRuntime().exec("fixture-command-never-executed", new String[0]);
    }
  }

  public static final class RuntimeStringDirectory {

    @SuppressWarnings("deprecation")
    public Process launch() throws IOException {
      return Runtime.getRuntime()
          .exec("fixture-command-never-executed", new String[0], new File("."));
    }
  }

  public record EngineDependency(EngineImplementationFixture engine) {}

  public record ProbeDependency(ProbeImplementationFixture probe) {}

  public record WorkerDependency(WorkerImplementationFixture worker) {}

  public static final class BenignRuntimeInspection {

    public int availableProcessors() {
      return Runtime.getRuntime().availableProcessors();
    }
  }

  @FunctionalInterface
  public interface RuntimeLauncher {
    Process launch(String[] command) throws IOException;
  }

  public static final class RuntimeMethodReference {

    public RuntimeLauncher launcher() {
      return Runtime.getRuntime()::exec;
    }
  }
}
