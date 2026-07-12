package com.streamarr.transcode.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;

@SpringBootApplication(proxyBeanMethods = false, exclude = TaskExecutionAutoConfiguration.class)
public class StreamarrTranscodeWorkerApplication {

  private StreamarrTranscodeWorkerApplication() {}

  public static void main(String[] args) {
    SpringApplication.run(StreamarrTranscodeWorkerApplication.class, args);
  }
}
