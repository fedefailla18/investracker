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
 * A single resumable unit of work inside a {@link SyncJob}: one symbol
 * (TRADES, cursor-paginated by trade id) or one time window (DEPOSITS /
 * WITHDRAWALS / FIAT_ORDERS / CONVERT_TRADES — Binance's history endpoints
 * for these expose no id cursor, only 90-day/30-day windows). A failure
 * marks only its own chunk FAILED; sibling chunks already COMPLETED keep
 * their state, so a retry re-does only what didn't finish.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sync_job_chunk")
public class SyncJobChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Type(type = "pg-uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_job_id", nullable = false)
    private SyncJob syncJob;

    /** e.g. "BTCUSDT" for a TRADES chunk, or "1609459200000-1617235200000" for a windowed chunk. */
    @Column(name = "chunk_key", nullable = false, length = 100)
    private String chunkKey;

    @Column(name = "window_start_time")
    private Long windowStartTime;

    @Column(name = "window_end_time")
    private Long windowEndTime;

    /** Last processed trade/order id (TRADES) — lets a chunk resume mid-symbol instead of from scratch. */
    @Column(name = "cursor_value")
    private Long cursorValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncChunkStatus status;

    @Column(name = "records_processed", nullable = false)
    @Builder.Default
    private int recordsProcessed = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
