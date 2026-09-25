package com.importer.fileimporter.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs once per process start, after the app is fully up. Sync jobs left PENDING/RUNNING at this
 * point belong to a previous process instance that died (crash, redeploy, a dev restart) with
 * work still in flight — see BinanceFullSyncService.reconcileOrphanedJobs for what "reconciled"
 * means. Only Binance is job-tracked today; MexC's full sync is still the older fire-and-forget
 * design and isn't covered here.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SyncJobStartupReconciler {

    private final BinanceFullSyncService binanceFullSyncService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        binanceFullSyncService.reconcileOrphanedJobs();
    }
}
