package com.streamarr.server.repositories.task;

import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.domain.task.FileProcessingTaskStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FileProcessingTaskRepository
    extends JpaRepository<FileProcessingTask, UUID>, FileProcessingTaskRepositoryCustom {

  Optional<FileProcessingTask> findByFilepathUriAndStatusIn(
      String filepathUri, List<FileProcessingTaskStatus> statuses);

  List<FileProcessingTask> findByOwnerInstanceId(String ownerInstanceId);

  void deleteByFilepathUriAndStatusIn(String filepathUri, List<FileProcessingTaskStatus> statuses);
}
