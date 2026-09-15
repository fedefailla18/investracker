package com.importer.fileimporter.dto;

import com.importer.fileimporter.entity.SyncChunkStatus;
import com.importer.fileimporter.entity.SyncEntityType;
import com.importer.fileimporter.entity.SyncJob;
import com.importer.fileimporter.entity.SyncJobChunk;
import com.importer.fileimporter.entity.SyncJobStatus;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Value
@Builder
public class SyncJobResponse {
    UUID id;
    UUID batchId;
    SyncEntityType entityType;
    SyncJobStatus status;
    Long requestedStartTime;
    Long requestedEndTime;
    String errorMessage;
    int attemptCount;
    int chunksTotal;
    int chunksCompleted;
    int chunksFailed;
    LocalDateTime createdAt;
    LocalDateTime startedAt;
    LocalDateTime finishedAt;

    public static SyncJobResponse of(SyncJob job, List<SyncJobChunk> chunks) {
        int completed = (int) chunks.stream().filter(c -> c.getStatus() == SyncChunkStatus.COMPLETED).count();
        int failed = (int) chunks.stream().filter(c -> c.getStatus() == SyncChunkStatus.FAILED).count();
        return SyncJobResponse.builder()
                .id(job.getId())
                .batchId(job.getBatchId())
                .entityType(job.getEntityType())
                .status(job.getStatus())
                .requestedStartTime(job.getRequestedStartTime())
                .requestedEndTime(job.getRequestedEndTime())
                .errorMessage(job.getErrorMessage())
                .attemptCount(job.getAttemptCount())
                .chunksTotal(chunks.size())
                .chunksCompleted(completed)
                .chunksFailed(failed)
                .createdAt(job.getCreatedAt())
                .startedAt(job.getStartedAt())
                .finishedAt(job.getFinishedAt())
                .build();
    }
}
