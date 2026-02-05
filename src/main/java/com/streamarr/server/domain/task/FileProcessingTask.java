package com.streamarr.server.domain.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "file_processing_task")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileProcessingTask {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  @Column(updatable = false)
  private UUID id;

  @Column(nullable = false)
  private String filepath;

  @Column(name = "library_id", nullable = false)
  private UUID libraryId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(nullable = false)
  private FileProcessingTaskStatus status;

  @Column(name = "owner_instance_id")
  private String ownerInstanceId;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "created_on", nullable = false, updatable = false)
  private Instant createdOn;

  @Column(name = "completed_on")
  private Instant completedOn;
}
