package com.streamarr.server.config;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FileSystemConfiguration {

  @Bean
  FileSystem fileSystem() {
    return FileSystems.getDefault();
  }
}
