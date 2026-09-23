package com.importer.fileimporter.repository;

import com.importer.fileimporter.entity.ExchangeName;
import com.importer.fileimporter.entity.SyncEntityType;
import com.importer.fileimporter.entity.SyncJob;
import com.importer.fileimporter.entity.SyncJobStatus;
import com.importer.fileimporter.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
