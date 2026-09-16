package com.importer.fileimporter.service;

import com.importer.fileimporter.dto.BinanceApiTransactionAdapter;
import com.importer.fileimporter.dto.integration.binance.*;
import com.importer.fileimporter.entity.*;
import com.importer.fileimporter.repository.SyncJobChunkRepository;
import com.importer.fileimporter.repository.SyncJobRepository;
import com.importer.fileimporter.repository.UserExchangeConfigRepository;
import com.importer.fileimporter.utils.DateUtils;
import com.importer.fileimporter.utils.OperationUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates a Binance full-history sync as a set of resumable, independently-retryable
 * {@link SyncJob}s (one per {@link SyncEntityType}), each broken into {@link SyncJobChunk}s
 * (one per symbol for TRADES, one per 90/30-day window for the rest).
 *
 * Deliberately NOT wrapped in a class- or method-level {@code @Transactional}: that was the
 * root cause of the old design losing all progress on any mid-run failure (a single multi-hour
 * transaction spanning the whole sync). Every repository save here commits on its own — each is
 * its own implicit transaction — so job/chunk state and every persisted Transaction survive a
 * crash at (almost) the exact point it happened.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BinanceFullSyncService {

    private final BinanceApiService binanceApiService;
    private final UserExchangeConfigRepository userExchangeConfigRepository;
    private final EncryptionService encryptionService;
    private final TransactionService transactionService;
    private final PortfolioService portfolioService;
    private final RawResponseService rawResponseService;
    private final SyncJobRepository syncJobRepository;
    private final SyncJobChunkRepository syncJobChunkRepository;

    private static final long WINDOW_90_DAYS = 90L * 24 * 60 * 60 * 1000;
    private static final long WINDOW_30_DAYS = 30L * 24 * 60 * 60 * 1000;
    private static final long START_TIME_2017 = 1483228800000L; // 2017-01-01
    private static final List<SyncJobStatus> ACTIVE_STATUSES = List.of(SyncJobStatus.PENDING, SyncJobStatus.RUNNING);
    private static final String FIAT_DEPOSIT_PREFIX = "D:";
    private static final String FIAT_WITHDRAW_PREFIX = "W:";

    /**
     * Creates one {@link SyncJob} + its chunks per {@link SyncEntityType}, all sharing a batch id.
     * Throws {@link SyncJobAlreadyRunningException} if a job of any of those types is already
     * PENDING/RUNNING for this user+exchange (also enforced at the DB level by a partial unique
     * index, so a race between two concurrent requests can't create duplicates either).
     */
    public List<SyncJob> createSyncJobs(User user, String portfolioName, Long startDateEpochMs, Long endDateEpochMs) {
        UserExchangeConfig config = userExchangeConfigRepository.findByUserAndExchangeName(user, ExchangeName.BINANCE)
                .orElseThrow(() -> new IllegalArgumentException("Binance API keys not configured for user"));

        for (SyncEntityType type : SyncEntityType.values()) {
            syncJobRepository.findByUserAndExchangeNameAndEntityTypeAndStatusIn(user, ExchangeName.BINANCE, type, ACTIVE_STATUSES)
                    .ifPresent(existing -> { throw new SyncJobAlreadyRunningException(type); });
        }

        String apiKey = config.getApiKey();
        String secretKey = encryptionService.decrypt(config.getApiSecret());
        Portfolio portfolio = portfolioService.resolveExchangePortfolio(portfolioName, ExchangeName.BINANCE);

        long startTime = startDateEpochMs != null ? startDateEpochMs : START_TIME_2017;
        long endTime = endDateEpochMs != null ? endDateEpochMs : System.currentTimeMillis();
        UUID batchId = UUID.randomUUID();

        List<SyncJob> jobs = new ArrayList<>();
        for (SyncEntityType type : SyncEntityType.values()) {
            SyncJob job = SyncJob.builder()
                    .user(user).portfolio(portfolio).exchangeName(ExchangeName.BINANCE).entityType(type)
                    .batchId(batchId).requestedStartTime(startTime).requestedEndTime(endTime)
                    .status(SyncJobStatus.PENDING).createdAt(LocalDateTime.now())
                    .build();
            try {
                job = syncJobRepository.save(job);
            } catch (DataIntegrityViolationException e) {
                throw new SyncJobAlreadyRunningException(type);
            }
            List<SyncJobChunk> chunks = buildChunks(type, job, apiKey, secretKey, startTime, endTime);
            syncJobChunkRepository.saveAll(chunks);
            jobs.add(job);
        }
        return jobs;
    }

    private List<SyncJobChunk> buildChunks(SyncEntityType type, SyncJob job, String apiKey, String secretKey, long start, long end) {
        switch (type) {
            case DEPOSITS:
            case WITHDRAWALS:
                return windowedChunks(job, start, end, WINDOW_90_DAYS, "");
            case CONVERT_TRADES:
                return windowedChunks(job, start, end, WINDOW_30_DAYS, "");
            case FIAT_ORDERS:
                List<SyncJobChunk> fiatChunks = new ArrayList<>(windowedChunks(job, start, end, WINDOW_90_DAYS, FIAT_DEPOSIT_PREFIX));
                fiatChunks.addAll(windowedChunks(job, start, end, WINDOW_90_DAYS, FIAT_WITHDRAW_PREFIX));
                return fiatChunks;
            case TRADES:
                return tradeSymbolChunks(job, apiKey, secretKey);
            default:
                throw new IllegalStateException("Unhandled sync entity type: " + type);
        }
    }

    private List<SyncJobChunk> windowedChunks(SyncJob job, long start, long end, long windowSize, String keyPrefix) {
        List<SyncJobChunk> chunks = new ArrayList<>();
        for (long t = start; t < end; t += windowSize) {
            long e = Math.min(t + windowSize, end);
            chunks.add(SyncJobChunk.builder()
                    .syncJob(job)
                    .chunkKey(keyPrefix + t + "-" + e)
                    .windowStartTime(t)
                    .windowEndTime(e)
                    .status(SyncChunkStatus.PENDING)
                    .build());
        }
        return chunks;
    }

    private List<SyncJobChunk> tradeSymbolChunks(SyncJob job, String apiKey, String secretKey) {
        BinanceAccountResponse account = binanceApiService.getAccountInfo(apiKey, secretKey);
        Set<String> assets = account.getBalances().stream()
                .filter(b -> b.getFree().add(b.getLocked()).compareTo(BigDecimal.ZERO) > 0)
                .map(BinanceAccountResponse.AssetBalance::getAsset)
                .collect(Collectors.toSet());

        BinanceExchangeInfoResponse exchangeInfo = binanceApiService.getExchangeInfo();
        return exchangeInfo.getSymbols().stream()
                .filter(s -> assets.contains(s.getBaseAsset()) || assets.contains(s.getQuoteAsset()))
                .map(s -> SyncJobChunk.builder()
                        .syncJob(job)
                        .chunkKey(s.getSymbol())
                        .status(SyncChunkStatus.PENDING)
                        .build())
                .collect(Collectors.toList());
    }

    /** Runs (or resumes) a job: executes every chunk not already COMPLETED, in order. */
    public void runJob(UUID jobId) {
        SyncJob job = syncJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Sync job not found: " + jobId));

        job.setStatus(SyncJobStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        syncJobRepository.save(job);

        UserExchangeConfig config = userExchangeConfigRepository.findByUserAndExchangeName(job.getUser(), ExchangeName.BINANCE)
                .orElseThrow(() -> new IllegalArgumentException("Binance API keys not configured for user"));
        String apiKey = config.getApiKey();
        String secretKey = encryptionService.decrypt(config.getApiSecret());
        Portfolio portfolio = job.getPortfolio();

        Map<String, BinanceExchangeInfoResponse.SymbolInfo> symbolInfoMap = null;
        if (job.getEntityType() == SyncEntityType.TRADES) {
            symbolInfoMap = binanceApiService.getExchangeInfo().getSymbols().stream()
                    .collect(Collectors.toMap(BinanceExchangeInfoResponse.SymbolInfo::getSymbol, s -> s, (a, b) -> a));
        }

        List<SyncJobChunk> chunks = syncJobChunkRepository.findBySyncJobOrderByChunkKey(job);
        List<String> failedKeys = new ArrayList<>();
        for (SyncJobChunk chunk : chunks) {
            if (chunk.getStatus() == SyncChunkStatus.COMPLETED) {
                continue; // already done in a previous attempt — resume means skipping this, not redoing it
            }
            boolean ok = runChunk(job, chunk, apiKey, secretKey, portfolio, symbolInfoMap);
            if (!ok) {
                failedKeys.add(chunk.getChunkKey());
            }
            sleep(500);
        }

        job.setStatus(failedKeys.isEmpty() ? SyncJobStatus.COMPLETED : SyncJobStatus.FAILED);
        job.setFinishedAt(LocalDateTime.now());
        job.setErrorMessage(failedKeys.isEmpty() ? null : "Failed chunks: " + String.join(", ", failedKeys));
        syncJobRepository.save(job);

        maybeAdvanceLastSyncTimestamp(job);
    }

    /** Resets a FAILED job's FAILED chunks back to PENDING (COMPLETED chunks are left untouched) and re-runs it. */
    public void retryJob(UUID jobId) {
        SyncJob job = syncJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Sync job not found: " + jobId));
        if (job.getStatus() != SyncJobStatus.FAILED) {
            throw new IllegalStateException("Only a FAILED job can be retried (current status: " + job.getStatus() + ")");
        }

        List<SyncJobChunk> chunks = syncJobChunkRepository.findBySyncJobOrderByChunkKey(job);
        for (SyncJobChunk chunk : chunks) {
            if (chunk.getStatus() == SyncChunkStatus.FAILED) {
                chunk.setStatus(SyncChunkStatus.PENDING);
                chunk.setErrorMessage(null);
                syncJobChunkRepository.save(chunk);
            }
        }

        job.setStatus(SyncJobStatus.PENDING);
        job.setAttemptCount(job.getAttemptCount() + 1);
        job.setErrorMessage(null);
        job.setStartedAt(null);
        job.setFinishedAt(null);
        syncJobRepository.save(job);

        runJob(jobId);
    }

    /**
     * Only advances `lastSyncTimestamp` (used by the incremental /sync/binance endpoint) once
     * every job in the batch has COMPLETED — a partially-failed full sync must not make the
     * incremental path assume history is complete up to the requested end date.
     */
    private void maybeAdvanceLastSyncTimestamp(SyncJob job) {
        List<SyncJob> batchJobs = syncJobRepository.findByBatchIdOrderByEntityType(job.getBatchId());
        boolean allCompleted = batchJobs.stream().allMatch(j -> j.getStatus() == SyncJobStatus.COMPLETED);
        if (!allCompleted) {
            return;
        }
        UserExchangeConfig config = userExchangeConfigRepository.findByUserAndExchangeName(job.getUser(), ExchangeName.BINANCE)
                .orElse(null);
        if (config != null) {
            config.setLastSyncTimestamp(job.getRequestedEndTime());
            userExchangeConfigRepository.save(config);
        }
    }

    private boolean runChunk(SyncJob job, SyncJobChunk chunk, String apiKey, String secretKey, Portfolio portfolio,
                              Map<String, BinanceExchangeInfoResponse.SymbolInfo> symbolInfoMap) {
        chunk.setStatus(SyncChunkStatus.RUNNING);
        chunk.setStartedAt(LocalDateTime.now());
        syncJobChunkRepository.save(chunk);

        try {
            switch (job.getEntityType()) {
                case DEPOSITS:
                    runDepositsChunk(chunk, apiKey, secretKey, portfolio);
                    break;
                case WITHDRAWALS:
                    runWithdrawalsChunk(chunk, apiKey, secretKey, portfolio);
                    break;
                case FIAT_ORDERS:
                    runFiatOrdersChunk(chunk, apiKey, secretKey, portfolio);
                    break;
                case CONVERT_TRADES:
                    runConvertTradesChunk(chunk, apiKey, secretKey, portfolio);
                    break;
                case TRADES:
                    runTradesChunk(chunk, job.getRequestedStartTime(), apiKey, secretKey, portfolio, symbolInfoMap);
                    break;
                default:
                    throw new IllegalStateException("Unhandled sync entity type: " + job.getEntityType());
            }
            chunk.setStatus(SyncChunkStatus.COMPLETED);
            chunk.setErrorMessage(null);
        } catch (Exception ex) {
            log.error("Chunk {} ({}) failed: {}", chunk.getChunkKey(), job.getEntityType(), ex.getMessage(), ex);
            chunk.setStatus(SyncChunkStatus.FAILED);
            chunk.setErrorMessage(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        } finally {
            chunk.setFinishedAt(LocalDateTime.now());
            syncJobChunkRepository.save(chunk);
        }
        return chunk.getStatus() == SyncChunkStatus.COMPLETED;
    }

    private void runDepositsChunk(SyncJobChunk chunk, String apiKey, String secretKey, Portfolio portfolio) {
        List<BinanceDepositResponse> deposits = binanceApiService.getDepositHistory(
                apiKey, secretKey, chunk.getWindowStartTime(), chunk.getWindowEndTime());
        if (deposits == null || deposits.isEmpty()) {
            return;
        }
        rawResponseService.saveResponse(portfolio.getUser(), ExchangeName.BINANCE, "DEPOSIT", null, deposits);
        int processed = 0;
        for (BinanceDepositResponse d : deposits) {
            Transaction tx = Transaction.builder()
                    .dateUtc(DateUtils.toLocalDateTime(d.getInsertTime()))
                    .side(OperationUtils.DEPOSIT_STRING)
                    .symbol(d.getCoin())
                    .executed(d.getAmount())
                    .price(BigDecimal.ZERO)
                    .pair(d.getCoin() + "EXTERNAL")
                    .externalId(d.getTxId())
                    .exchangeName(ExchangeName.BINANCE)
                    .created(LocalDateTime.now())
                    .createdBy("BinanceFullSync-Deposit")
                    .portfolio(portfolio)
                    .build();
            transactionService.saveIfAbsent(tx);
            processed++;
        }
        chunk.setRecordsProcessed(processed);
    }

    private void runWithdrawalsChunk(SyncJobChunk chunk, String apiKey, String secretKey, Portfolio portfolio) {
        List<BinanceWithdrawResponse> withdrawals = binanceApiService.getWithdrawHistory(
                apiKey, secretKey, chunk.getWindowStartTime(), chunk.getWindowEndTime());
        if (withdrawals == null || withdrawals.isEmpty()) {
            return;
        }
        rawResponseService.saveResponse(portfolio.getUser(), ExchangeName.BINANCE, "WITHDRAW", null, withdrawals);
        int processed = 0;
        for (BinanceWithdrawResponse w : withdrawals) {
            Transaction tx = Transaction.builder()
                    .dateUtc(DateUtils.getLocalDateTime(w.getApplyTime()))
                    .side(OperationUtils.WITHDRAW_STRING)
                    .symbol(w.getCoin())
                    .executed(w.getAmount())
                    .price(BigDecimal.ZERO)
                    .pair(w.getCoin() + "EXTERNAL")
                    .feeAmount(w.getTransactionFee())
                    .feeSymbol(w.getCoin())
                    .externalId(w.getTxId() != null ? w.getTxId() : w.getId())
                    .exchangeName(ExchangeName.BINANCE)
                    .created(LocalDateTime.now())
                    .createdBy("BinanceFullSync-Withdraw")
                    .portfolio(portfolio)
                    .build();
            transactionService.saveIfAbsent(tx);
            processed++;
        }
        chunk.setRecordsProcessed(processed);
    }

    private void runFiatOrdersChunk(SyncJobChunk chunk, String apiKey, String secretKey, Portfolio portfolio) {
        boolean isDeposit = chunk.getChunkKey().startsWith(FIAT_DEPOSIT_PREFIX);
        int transactionType = isDeposit ? 0 : 1;

        BinanceFiatOrderResponse resp = binanceApiService.getFiatOrders(
                apiKey, secretKey, transactionType, chunk.getWindowStartTime(), chunk.getWindowEndTime());
        if (resp == null || resp.getData() == null || resp.getData().isEmpty()) {
            return;
        }
        rawResponseService.saveResponse(portfolio.getUser(), ExchangeName.BINANCE, "FIAT_ORDER_" + transactionType, null, resp);
        int processed = 0;
        for (BinanceFiatOrderResponse.FiatOrder o : resp.getData()) {
            if (!"Completed".equalsIgnoreCase(o.getStatus())) {
                continue;
            }
            Transaction tx = Transaction.builder()
                    .dateUtc(DateUtils.toLocalDateTime(o.getCreateTime()))
                    .side(isDeposit ? OperationUtils.DEPOSIT_STRING : OperationUtils.WITHDRAW_STRING)
                    .symbol(o.getCryptoCurrency())
                    .executed(new BigDecimal(o.getObtainAmount()))
                    .price(new BigDecimal(o.getPrice()))
                    .paidWith(o.getFiatCurrency())
                    .paidAmount(new BigDecimal(o.getSourceAmount()))
                    .pair(o.getCryptoCurrency() + o.getFiatCurrency())
                    .feeAmount(o.getTotalFee())
                    .feeSymbol(o.getFiatCurrency())
                    .externalId(o.getOrderNo())
                    .exchangeName(ExchangeName.BINANCE)
                    .created(LocalDateTime.now())
                    .createdBy("BinanceFullSync-FiatOrder")
                    .portfolio(portfolio)
                    .build();
            transactionService.saveIfAbsent(tx);
            processed++;
        }
        chunk.setRecordsProcessed(processed);
    }

    private void runConvertTradesChunk(SyncJobChunk chunk, String apiKey, String secretKey, Portfolio portfolio) {
        BinanceConvertTradeResponse resp = binanceApiService.getConvertTradeHistory(
                apiKey, secretKey, chunk.getWindowStartTime(), chunk.getWindowEndTime());
        if (resp == null || resp.getList() == null || resp.getList().isEmpty()) {
            return;
        }
        rawResponseService.saveResponse(portfolio.getUser(), ExchangeName.BINANCE, "CONVERT_TRADE", null, resp);
        int processed = 0;
        for (BinanceConvertTradeResponse.ConvertTrade c : resp.getList()) {
            if (!"SUCCESS".equalsIgnoreCase(c.getOrderStatus())) {
                continue;
            }
            Transaction tx = Transaction.builder()
                    .dateUtc(DateUtils.toLocalDateTime(c.getCreateTime()))
                    .side(OperationUtils.BUY_STRING)
                    .symbol(c.getToAsset())
                    .executed(c.getToAmount())
                    .price(c.getRatio())
                    .paidWith(c.getFromAsset())
                    .paidAmount(c.getFromAmount())
                    .pair(c.getToAsset() + c.getFromAsset())
                    .externalId(c.getOrderId())
                    .exchangeName(ExchangeName.BINANCE)
                    .created(LocalDateTime.now())
                    .createdBy("BinanceFullSync-Convert")
                    .portfolio(portfolio)
                    .build();
            transactionService.saveIfAbsent(tx);
            processed++;
        }
        chunk.setRecordsProcessed(processed);
    }

    private void runTradesChunk(SyncJobChunk chunk, long jobStartTime, String apiKey, String secretKey, Portfolio portfolio,
                                 Map<String, BinanceExchangeInfoResponse.SymbolInfo> symbolInfoMap) {
        String symbol = chunk.getChunkKey();
        BinanceExchangeInfoResponse.SymbolInfo sInfo = symbolInfoMap.get(symbol);
        if (sInfo == null) {
            log.debug("Symbol {} no longer listed on Binance; nothing to sync", symbol);
            return;
        }

        Long lastTradeId = chunk.getCursorValue();
        int processed = chunk.getRecordsProcessed();
        boolean hasMore = true;

        while (hasMore) {
            try {
                List<BinanceTradeResponse> trades = lastTradeId == null
                        ? binanceApiService.getMyTrades(apiKey, secretKey, symbol, jobStartTime, null, null)
                        : binanceApiService.getMyTrades(apiKey, secretKey, symbol, null, null, lastTradeId + 1);

                if (trades == null || trades.isEmpty()) {
                    hasMore = false;
                } else {
                    rawResponseService.saveResponse(portfolio.getUser(), ExchangeName.BINANCE, "TRADES_" + symbol, null, trades);
                    for (BinanceTradeResponse tr : trades) {
                        BinanceApiTransactionAdapter adapter = new BinanceApiTransactionAdapter(tr, sInfo.getBaseAsset(), sInfo.getQuoteAsset());
                        Transaction tx = Transaction.builder()
                                .dateUtc(DateUtils.getLocalDateTime(adapter.getDate()))
                                .pair(adapter.getPair())
                                .executed(adapter.getExecuted())
                                .side(adapter.getSide())
                                .price(adapter.getPrice())
                                .symbol(adapter.getSymbol())
                                .paidWith(adapter.getPaidWith())
                                .paidAmount(adapter.getAmount())
                                .feeAmount(adapter.getFee())
                                .feeSymbol(adapter.getFeeSymbol())
                                .externalId(tr.getId().toString())
                                .exchangeName(ExchangeName.BINANCE)
                                .created(LocalDateTime.now())
                                .createdBy("BinanceFullSync-Spot")
                                .portfolio(portfolio)
                                .build();
                        transactionService.saveIfAbsent(tx);
                        lastTradeId = tr.getId();
                        processed++;
                    }
                    // Checkpoint after every page (not just at chunk end) — a crash mid-symbol
                    // resumes from the last completed page instead of refetching this symbol's
                    // entire history from scratch.
                    chunk.setCursorValue(lastTradeId);
                    chunk.setRecordsProcessed(processed);
                    syncJobChunkRepository.save(chunk);
                    if (trades.size() < 1000) {
                        hasMore = false;
                    }
                }
            } catch (Exception ex) {
                if (ex.getMessage() != null && ex.getMessage().contains("-1121")) {
                    log.debug("Symbol {} no longer exists or invalid", symbol);
                    hasMore = false;
                } else {
                    throw ex;
                }
            }
            sleep(200);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
