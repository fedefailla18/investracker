package com.importer.fileimporter.service;

import com.importer.fileimporter.dto.SyncStatusMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SyncNotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    public void notifyCompleted(String username, String portfolioName) {
        send(username, new SyncStatusMessage(portfolioName, "COMPLETED",
                "Full historical sync completed for portfolio: " + portfolioName, null));
    }

    public void notifyFailed(String username, String portfolioName, String error) {
        send(username, new SyncStatusMessage(portfolioName, "FAILED",
                error != null ? error : "Unknown error", null));
    }

    /**
     * A job-model sync finished (COMPLETED or FAILED, check the job itself via
     * GET /transaction/sync/binance/jobs/{jobId}) — this message is a "go refetch" ping, not the
     * full status, so the frontend has one source of truth (the jobs table) instead of trusting
     * a socket payload that might arrive out of order or be missed entirely.
     */
    public void notifyJobFinished(String username, UUID jobId) {
        send(username, new SyncStatusMessage(null, "JOB_FINISHED", "Sync job finished", jobId.toString()));
    }

    public void notifyJobCrashed(String username, UUID jobId, String error) {
        send(username, new SyncStatusMessage(null, "JOB_CRASHED",
                error != null ? error : "Unknown error", jobId.toString()));
    }

    private void send(String username, SyncStatusMessage msg) {
        try {
            messagingTemplate.convertAndSendToUser(username, "/queue/sync-status", msg);
            log.info("Sent sync status [{}] to user {}", msg.getStatus(), username);
        } catch (Exception e) {
            log.warn("Failed to push WebSocket notification to {}: {}", username, e.getMessage());
        }
    }
}
