package com.importer.fileimporter.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SyncStatusMessage {
    private String portfolioName;
    /** "COMPLETED", "FAILED" (legacy path), or "JOB_FINISHED" / "JOB_CRASHED" (job-model path) */
    private String status;
    private String message;
    /** Present for job-model messages — the frontend refetches GET /transaction/sync/binance/jobs/{jobId}. */
    private String jobId;
}
