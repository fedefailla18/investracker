package com.importer.fileimporter.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row per {@link SyncEntityType} per full-sync request (so a batch of
 * "sync everything" creates up to 5 of these). Tracks job-level state
 * (PENDING/RUNNING/COMPLETED/FAILED); the actual resumable work units live
 * in {@link SyncJobChunk}. Deliberately separate from the pre-existing
 * `binance_sync_progress`/`BinanceSyncProgress` table, which backs a
 * different, narrower feature (raw per-symbol order diagnostics via
 * BinanceIntegrationController) — not touched by this model.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sync_job")
public class SyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Type(type = "pg-uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    @Enumerated(EnumType.STRING)
    @Column(name = "exchange_name", nullable = false)
    private ExchangeName exchangeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private SyncEntityType entityType;

    /** Correlates the (up to 5) jobs created from a single "Full Sync" trigger. */
    @Column(name = "batch_id", nullable = false)
    @Type(type = "pg-uuid")
    private UUID batchId;

    @Column(name = "requested_start_time", nullable = false)
    private Long requestedStartTime;

    @Column(name = "requested_end_time", nullable = false)
    private Long requestedEndTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncJobStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
