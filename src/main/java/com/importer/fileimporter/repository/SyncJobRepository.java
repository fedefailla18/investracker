package com.importer.fileimporter.repository;

import com.importer.fileimporter.entity.ExchangeName;
import com.importer.fileimporter.entity.SyncEntityType;
import com.importer.fileimporter.entity.SyncJob;
import com.importer.fileimporter.entity.SyncJobStatus;
import com.importer.fileimporter.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SyncJobRepository extends JpaRepository<SyncJob, UUID> {

    List<SyncJob> findByUserAndExchangeNameOrderByCreatedAtDesc(User user, ExchangeName exchangeName);

    List<SyncJob> findByBatchIdOrderByEntityType(UUID batchId);

    Optional<SyncJob> findByUserAndExchangeNameAndEntityTypeAndStatusIn(
            User user, ExchangeName exchangeName, SyncEntityType entityType, Collection<SyncJobStatus> statuses);

    /** Used at startup to find jobs orphaned by a previous process dying mid-run — see SyncJobStartupReconciler. */
    List<SyncJob> findByStatusIn(Collection<SyncJobStatus> statuses);

    /**
     * Eagerly loads {@code job.portfolio} and {@code portfolio.user} (both LAZY) in the same
     * query/session, so callers can use them after this method returns without needing an open
     * Hibernate session — required because runJob/retryJob deliberately run with no spanning
     * {@code @Transactional} (see BinanceFullSyncService's class javadoc). A plain findById here
     * would hand back lazy proxies that throw LazyInitializationException the moment a chunk
     * runner calls portfolio.getUser().
     */
    @Query("SELECT j FROM SyncJob j JOIN FETCH j.portfolio p JOIN FETCH p.user WHERE j.id = :id")
    Optional<SyncJob> findByIdWithPortfolioAndUser(@Param("id") UUID id);
}
