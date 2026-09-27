package com.importer.fileimporter.dto;

import com.importer.fileimporter.entity.SyncChunkStatus;
import com.importer.fileimporter.entity.SyncJobChunk;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.UUID;

@Value
@Builder
public class SyncJobChunkResponse {
    UUID id;
    String chunkKey;
    Long windowStartTime;
    Long windowEndTime;
    SyncChunkStatus status;
    int recordsProcessed;
    String errorMessage;
    LocalDateTime startedAt;
    LocalDateTime finishedAt;

    public static SyncJobChunkResponse of(SyncJobChunk chunk) {
        return SyncJobChunkResponse.builder()
                .id(chunk.getId())
                .chunkKey(chunk.getChunkKey())
                .windowStartTime(chunk.getWindowStartTime())
                .windowEndTime(chunk.getWindowEndTime())
                .status(chunk.getStatus())
                .recordsProcessed(chunk.getRecordsProcessed())
                .errorMessage(chunk.getErrorMessage())
                .startedAt(chunk.getStartedAt())
                .finishedAt(chunk.getFinishedAt())
                .build();
    }
}
