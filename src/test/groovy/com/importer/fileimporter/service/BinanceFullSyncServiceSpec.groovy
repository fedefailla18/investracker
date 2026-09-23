package com.importer.fileimporter.service

import com.importer.fileimporter.dto.integration.binance.BinanceAccountResponse
import com.importer.fileimporter.dto.integration.binance.BinanceDepositResponse
import com.importer.fileimporter.dto.integration.binance.BinanceExchangeInfoResponse
import com.importer.fileimporter.entity.ExchangeName
import com.importer.fileimporter.entity.Portfolio
import com.importer.fileimporter.entity.SyncChunkStatus
import com.importer.fileimporter.entity.SyncEntityType
import com.importer.fileimporter.entity.SyncJob
import com.importer.fileimporter.entity.SyncJobChunk
import com.importer.fileimporter.entity.SyncJobStatus
import com.importer.fileimporter.entity.User
import com.importer.fileimporter.entity.UserExchangeConfig
import com.importer.fileimporter.repository.SyncJobChunkRepository
import com.importer.fileimporter.repository.SyncJobRepository
import com.importer.fileimporter.repository.UserExchangeConfigRepository
import spock.lang.Specification

import java.util.stream.Collectors

class BinanceFullSyncServiceSpec extends Specification {

    def binanceApiService = Mock(BinanceApiService)
    def userExchangeConfigRepository = Mock(UserExchangeConfigRepository)
    def encryptionService = Mock(EncryptionService)
    def transactionService = Mock(TransactionService)
    def portfolioService = Mock(PortfolioService)
    def rawResponseService = Mock(RawResponseService)
    def syncJobRepository = Mock(SyncJobRepository)
    def syncJobChunkRepository = Mock(SyncJobChunkRepository)

    BinanceFullSyncService service = new BinanceFullSyncService(
            binanceApiService, userExchangeConfigRepository, encryptionService, transactionService,
            portfolioService, rawResponseService, syncJobRepository, syncJobChunkRepository)

    User user = new User(username: "trader")
    Portfolio portfolio = new Portfolio(name: "main")
    UserExchangeConfig config = UserExchangeConfig.builder().apiKey("key").apiSecret("enc-secret").build()

    // Populated by the saveAll() stub below so tests can inspect exactly what chunks were
    // generated per entity type without needing a second, order-dependent stub declaration.
    Map<SyncEntityType, List<SyncJobChunk>> savedChunksByType = [:]

    def setup() {
        userExchangeConfigRepository.findByUserAndExchangeName(user, ExchangeName.BINANCE) >> Optional.of(config)
        encryptionService.decrypt("enc-secret") >> "secret"
        portfolioService.findOrSave("main", ExchangeName.BINANCE) >> portfolio
        // Every save just echoes back what it was given, mirroring a real JPA repo's contract.
        syncJobRepository.save(_) >> { SyncJob j -> j }
        syncJobChunkRepository.save(_) >> { SyncJobChunk c -> c }
        syncJobChunkRepository.saveAll(_) >> { args ->
            // Spock always passes a single-parameter closure the FULL argument array for a call
            // with one List/Iterable argument, rather than unwrapping it — args[0] is the real list.
            List<SyncJobChunk> chunks = args[0] as List<SyncJobChunk>
            if (!chunks.isEmpty()) {
                savedChunksByType[chunks[0].syncJob.entityType] = chunks
            }
            chunks
        }
        // Real behavior: the batch always contains at least the job just saved. Tests below focus
        // on job/chunk state, not lastSyncTimestamp advancement, so an empty batch (never
        // "all completed") keeps that codepath inert unless a test opts in explicitly.
        syncJobRepository.findByBatchIdOrderByEntityType(_) >> []
    }

    def "createSyncJobs creates one job per entity type, sharing a batch id, and rejects overlapping active jobs"() {
        given: "no account balance, so TRADES gets zero symbol chunks — keeps this test focused on job creation"
        binanceApiService.getAccountInfo(*_) >> new BinanceAccountResponse(balances: [])
        binanceApiService.getExchangeInfo() >> new BinanceExchangeInfoResponse(symbols: [])
        syncJobRepository.findByUserAndExchangeNameAndEntityTypeAndStatusIn(*_) >> Optional.empty()

        when:
        def jobs = service.createSyncJobs(user, "main", 1000L, 2000L)

        then:
        jobs.size() == SyncEntityType.values().size()
        jobs*.entityType as Set == SyncEntityType.values() as Set
        jobs*.batchId.toSet().size() == 1
        jobs.every { it.status == SyncJobStatus.PENDING }
    }

    def "createSyncJobs refuses to start a second job of a type that's already PENDING or RUNNING"() {
        given:
        syncJobRepository.findByUserAndExchangeNameAndEntityTypeAndStatusIn(
                user, ExchangeName.BINANCE, SyncEntityType.TRADES, [SyncJobStatus.PENDING, SyncJobStatus.RUNNING]
        ) >> Optional.of(Mock(SyncJob))

        when:
        service.createSyncJobs(user, "main", 1000L, 2000L)

        then:
        thrown(SyncJobAlreadyRunningException)
        0 * binanceApiService.getAccountInfo(*_)
    }

    def "createSyncJobs splits a 200-day deposits window into three 90-day chunks"() {
        given:
        binanceApiService.getAccountInfo(*_) >> new BinanceAccountResponse(balances: [])
        binanceApiService.getExchangeInfo() >> new BinanceExchangeInfoResponse(symbols: [])
        syncJobRepository.findByUserAndExchangeNameAndEntityTypeAndStatusIn(*_) >> Optional.empty()
        long ninetyDays = 90L * 24 * 60 * 60 * 1000
        long start = 0L
        long end = ninetyDays * 2 + 1000 // just past two full windows -> 3 chunks

        when:
        service.createSyncJobs(user, "main", start, end)
        def depositsChunks = savedChunksByType[SyncEntityType.DEPOSITS]

        then:
        depositsChunks.size() == 3
        depositsChunks.every { it.status == SyncChunkStatus.PENDING }
    }

    def "runJob isolates a chunk failure: the failing chunk is marked FAILED, sibling chunks stay COMPLETED, and the job itself ends FAILED"() {
        given:
        SyncJob job = SyncJob.builder()
                .id(UUID.randomUUID()).user(user).portfolio(portfolio).exchangeName(ExchangeName.BINANCE)
                .entityType(SyncEntityType.DEPOSITS).batchId(UUID.randomUUID())
                .requestedStartTime(0L).requestedEndTime(1000L).status(SyncJobStatus.PENDING).build()

        SyncJobChunk okChunk = SyncJobChunk.builder().id(UUID.randomUUID()).syncJob(job)
                .chunkKey("0-500").windowStartTime(0L).windowEndTime(500L).status(SyncChunkStatus.PENDING).build()
        SyncJobChunk badChunk = SyncJobChunk.builder().id(UUID.randomUUID()).syncJob(job)
                .chunkKey("500-1000").windowStartTime(500L).windowEndTime(1000L).status(SyncChunkStatus.PENDING).build()

        syncJobRepository.findByIdWithPortfolioAndUser(job.id) >> Optional.of(job)
        syncJobChunkRepository.findBySyncJobOrderByChunkKey(job) >> [okChunk, badChunk]
        binanceApiService.getDepositHistory(*_) >>
                [new BinanceDepositResponse(coin: "BTC", amount: BigDecimal.ONE, insertTime: 1L, txId: "tx1")] >>
                { throw new RuntimeException("Binance rate limit") }

        when:
        service.runJob(job.id)

        then:
        okChunk.status == SyncChunkStatus.COMPLETED
        badChunk.status == SyncChunkStatus.FAILED
        badChunk.errorMessage == "Binance rate limit"
        job.status == SyncJobStatus.FAILED
        job.errorMessage.contains("500-1000")
        !job.errorMessage.contains("0-500")
    }

    def "runJob resume skips chunks already COMPLETED from a previous attempt"() {
        given:
        SyncJob job = SyncJob.builder()
                .id(UUID.randomUUID()).user(user).portfolio(portfolio).exchangeName(ExchangeName.BINANCE)
                .entityType(SyncEntityType.DEPOSITS).batchId(UUID.randomUUID())
                .requestedStartTime(0L).requestedEndTime(1000L).status(SyncJobStatus.PENDING).build()

        SyncJobChunk alreadyDone = SyncJobChunk.builder().id(UUID.randomUUID()).syncJob(job)
                .chunkKey("0-500").windowStartTime(0L).windowEndTime(500L).status(SyncChunkStatus.COMPLETED).build()
        SyncJobChunk pending = SyncJobChunk.builder().id(UUID.randomUUID()).syncJob(job)
                .chunkKey("500-1000").windowStartTime(500L).windowEndTime(1000L).status(SyncChunkStatus.PENDING).build()

        syncJobRepository.findByIdWithPortfolioAndUser(job.id) >> Optional.of(job)
        syncJobChunkRepository.findBySyncJobOrderByChunkKey(job) >> [alreadyDone, pending]

        when:
        service.runJob(job.id)

        then: "the already-completed chunk's window is never re-fetched from Binance"
        0 * binanceApiService.getDepositHistory(_, _, 0L, 500L)
        1 * binanceApiService.getDepositHistory(_, _, 500L, 1000L) >> []
        job.status == SyncJobStatus.COMPLETED
    }

    def "retryJob resets only FAILED chunks back to PENDING and leaves COMPLETED chunks untouched"() {
        given:
        SyncJob job = SyncJob.builder()
                .id(UUID.randomUUID()).user(user).portfolio(portfolio).exchangeName(ExchangeName.BINANCE)
                .entityType(SyncEntityType.DEPOSITS).batchId(UUID.randomUUID()).attemptCount(0)
                .requestedStartTime(0L).requestedEndTime(1000L).status(SyncJobStatus.FAILED)
                .errorMessage("Failed chunks: 500-1000").build()

        SyncJobChunk completed = SyncJobChunk.builder().id(UUID.randomUUID()).syncJob(job)
                .chunkKey("0-500").windowStartTime(0L).windowEndTime(500L)
                .status(SyncChunkStatus.COMPLETED).recordsProcessed(2).build()
        SyncJobChunk failed = SyncJobChunk.builder().id(UUID.randomUUID()).syncJob(job)
                .chunkKey("500-1000").windowStartTime(500L).windowEndTime(1000L)
                .status(SyncChunkStatus.FAILED).errorMessage("boom").build()

        syncJobRepository.findById(job.id) >> Optional.of(job)
        syncJobRepository.findByIdWithPortfolioAndUser(job.id) >> Optional.of(job)
        syncJobChunkRepository.findBySyncJobOrderByChunkKey(job) >> [completed, failed]

        when:
        service.retryJob(job.id)

        then:
        0 * binanceApiService.getDepositHistory(_, _, 0L, 500L) // COMPLETED chunk is never redone
        1 * binanceApiService.getDepositHistory(_, _, 500L, 1000L) >> [] // only the FAILED chunk re-runs
        completed.status == SyncChunkStatus.COMPLETED // untouched
        job.attemptCount == 1
        job.status == SyncJobStatus.COMPLETED // the only previously-failed chunk now succeeds
    }

    def "retryJob refuses to retry a job that isn't currently FAILED"() {
        given:
        SyncJob job = SyncJob.builder().id(UUID.randomUUID()).status(SyncJobStatus.COMPLETED).build()
        syncJobRepository.findById(job.id) >> Optional.of(job)

        when:
        service.retryJob(job.id)

        then:
        thrown(IllegalStateException)
    }

    // ── reconcileOrphanedJobs ────────────────────────────────────────────────────

    def "reconcileOrphanedJobs marks a leftover PENDING/RUNNING job FAILED and resets its RUNNING chunks to PENDING"() {
        given: "a job never started (PENDING, no chunks touched) and one that was mid-flight (RUNNING)"
        SyncJob pendingJob = SyncJob.builder().id(UUID.randomUUID()).entityType(SyncEntityType.DEPOSITS)
                .status(SyncJobStatus.PENDING).build()
        SyncJob runningJob = SyncJob.builder().id(UUID.randomUUID()).entityType(SyncEntityType.TRADES)
                .status(SyncJobStatus.RUNNING).build()

        SyncJobChunk stillPending = SyncJobChunk.builder().id(UUID.randomUUID()).syncJob(pendingJob)
                .chunkKey("0-500").status(SyncChunkStatus.PENDING).build()
        SyncJobChunk completed = SyncJobChunk.builder().id(UUID.randomUUID()).syncJob(runningJob)
                .chunkKey("BTCUSDT").status(SyncChunkStatus.COMPLETED).recordsProcessed(40).build()
        SyncJobChunk stuckRunning = SyncJobChunk.builder().id(UUID.randomUUID()).syncJob(runningJob)
                .chunkKey("ETHUSDT").status(SyncChunkStatus.RUNNING).cursorValue(12345L).build()

        syncJobRepository.findByStatusIn(_) >> [pendingJob, runningJob]
        syncJobChunkRepository.findBySyncJobOrderByChunkKey(pendingJob) >> [stillPending]
        syncJobChunkRepository.findBySyncJobOrderByChunkKey(runningJob) >> [completed, stuckRunning]

        when:
        service.reconcileOrphanedJobs()

        then:
        pendingJob.status == SyncJobStatus.FAILED
        runningJob.status == SyncJobStatus.FAILED
        pendingJob.errorMessage.contains("restart")
        stillPending.status == SyncChunkStatus.PENDING // untouched, was already PENDING
        completed.status == SyncChunkStatus.COMPLETED // untouched — this is the whole point
        stuckRunning.status == SyncChunkStatus.PENDING // reset so retry redoes only this one
        stuckRunning.cursorValue == 12345L // checkpoint preserved — resumes mid-symbol, not from scratch
    }

    def "reconcileOrphanedJobs does nothing when there are no leftover jobs"() {
        given:
        syncJobRepository.findByStatusIn(_) >> []

        when:
        service.reconcileOrphanedJobs()

        then:
        0 * syncJobRepository.save(_)
        0 * syncJobChunkRepository.save(_)
    }
}
