package com.importer.fileimporter.dto;

import com.importer.fileimporter.entity.SyncJob;
import com.importer.fileimporter.entity.SyncJobChunk;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.stream.Collectors;

@Value
@Builder
public class SyncJobDetailResponse {
    SyncJobResponse job;
    List<SyncJobChunkResponse> chunks;

    public static SyncJobDetailResponse of(SyncJob job, List<SyncJobChunk> chunks) {
        return SyncJobDetailResponse.builder()
                .job(SyncJobResponse.of(job, chunks))
                .chunks(chunks.stream().map(SyncJobChunkResponse::of).collect(Collectors.toList()))
                .build();
    }
}
