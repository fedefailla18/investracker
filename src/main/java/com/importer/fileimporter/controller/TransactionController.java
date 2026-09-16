package com.importer.fileimporter.controller;

import com.importer.fileimporter.dto.CoinInformationResponse;
import com.importer.fileimporter.dto.FileInformationResponse;
import com.importer.fileimporter.dto.PortfolioProcessingResult;
import com.importer.fileimporter.dto.SyncJobDetailResponse;
import com.importer.fileimporter.dto.SyncJobResponse;
import com.importer.fileimporter.dto.TransactionDto;
import com.importer.fileimporter.dto.TransactionHoldingDto;
import com.importer.fileimporter.entity.ExchangeName;
import com.importer.fileimporter.entity.SyncJob;
import com.importer.fileimporter.entity.SyncJobStatus;
import com.importer.fileimporter.entity.Transaction;
import com.importer.fileimporter.entity.User;
import com.importer.fileimporter.facade.CoinInformationFacade;
import com.importer.fileimporter.service.BinanceAsyncSyncService;
import com.importer.fileimporter.service.BinanceSyncService;
import com.importer.fileimporter.service.MexcAsyncSyncService;
import com.importer.fileimporter.service.MexcSyncService;
import com.importer.fileimporter.service.PortfolioNotExchangeOwnedException;
import com.importer.fileimporter.service.PortfolioService;
import com.importer.fileimporter.service.ProcessFileFactory;
import com.importer.fileimporter.service.SyncJobAlreadyRunningException;
import com.importer.fileimporter.service.TransactionFacade;
import com.importer.fileimporter.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping(value = "/transaction")
@Slf4j
@Tag(name = "Transaction", description = "Transaction management APIs")
public class TransactionController {

    private final ProcessFileFactory processFileFactory;
    private final TransactionService transactionService;
    private final TransactionFacade transactionFacade;
    private final CoinInformationFacade coinInformationFacade;
    private final BinanceSyncService binanceSyncService;
    private final MexcSyncService mexcSyncService;
    private final BinanceAsyncSyncService binanceAsyncSyncService;
    private final MexcAsyncSyncService mexcAsyncSyncService;
    private final PortfolioService portfolioService;
    private final com.importer.fileimporter.repository.SyncJobRepository syncJobRepository;
    private final com.importer.fileimporter.repository.SyncJobChunkRepository syncJobChunkRepository;

    @Operation(summary = "Filter transactions", description = "Filter transactions by various criteria with pagination")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Successfully retrieved transactions",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = Page.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @GetMapping(value = "/filter")
    public Page<Transaction> getTransactionsRangeDate(
            @Parameter(description = "Symbol to filter by") @RequestParam(required = false) String symbol,
            @Parameter(description = "Portfolio name to filter by") @RequestParam(required = false) String portfolioName,
            @Parameter(description = "Transaction side (BUY/SELL)") @RequestParam(required = false) String side,
            @Parameter(description = "Currency paid with") @RequestParam(required = false) String paidWith,
            @Parameter(description = "Operator for paid amount (>, <, =, >=, <=)") @RequestParam(required = false) String paidAmountOperator,
            @Parameter(description = "Amount paid") @RequestParam(required = false) BigDecimal paidAmount,
            @Parameter(description = "Start date (ISO format)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "End date (ISO format)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @AuthenticationPrincipal User user,
            @Parameter(description = "Pagination parameters") @PageableDefault(sort = "dateUtc", direction = Sort.Direction.DESC) Pageable pageable) {
        return transactionFacade.filterTransactions(symbol, portfolioName, side, paidWith, paidAmountOperator, paidAmount,
                startDate, endDate, user.getId(), pageable);
    }

    @PostMapping("/information")
    public CoinInformationResponse getSymbolInformation(@RequestParam String symbol) {
        return coinInformationFacade.getTransactionsInformationBySymbol(symbol);
    }

    @PostMapping("/information/all")
    public List<CoinInformationResponse> getInformation() {
        return coinInformationFacade.getTransactionsInformation();
    }

    @Tag(name = "/information/all/{portfolio}", description = "Calculates portfolio stats from transactions")
    @PostMapping("/information/all/{portfolio}")
    public List<CoinInformationResponse> getInformation(@PathVariable String portfolio) {
        return coinInformationFacade.getPortfolioTransactionsInformation(portfolio);
    }

    @Operation(summary = "Upload transaction file", description = "Upload and process a file containing transactions")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "File successfully processed",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = FileInformationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid file or format", content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PostMapping(value = "/upload")
    @Deprecated(since = "1.0.1", forRemoval = true)
    public FileInformationResponse uploadTransactions(
            @Parameter(description = "Transaction file to upload", required = true) @RequestParam("file") MultipartFile file,
            @Parameter(description = "List of symbols to filter by") @RequestParam(required = false) List<String> symbols) throws IOException {
        if (file.isEmpty()) {
            return null;
        }
        return processFileFactory.processFile(file, symbols);
    }

    @Operation(summary = "Upload transaction file to portfolio", description = "Upload and process a file containing transactions and assign to a specific portfolio")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "File successfully processed",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = FileInformationResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid file or format", content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PostMapping(value = "/upload/{portfolio}")
    public FileInformationResponse uploadTransactionsWithPortfolio(
            @Parameter(description = "Transaction file to upload", required = true) @RequestParam("file") MultipartFile file,
            @Parameter(description = "List of symbols to filter by") @RequestParam(required = false) List<String> symbols,
            @Parameter(description = "Portfolio name", required = true) @PathVariable String portfolio,
            @Parameter(description = "File type (BINANCE, MEXC)") @RequestParam(required = false, defaultValue = "Binance") String fileType) throws IOException {
        if (file.isEmpty()) {
            return null;
        }
        return processFileFactory.processFile(file, symbols, portfolio, fileType);
    }

    @GetMapping(value = "/portfolio")
    public List<TransactionHoldingDto> createPortfolio(@RequestParam(required = false) List<String> symbols) {
        return transactionFacade.buildPortfolio(symbols);
    }

    @GetMapping(value = "/portfolio/amount")
    public List<TransactionHoldingDto> getAmount(@RequestParam(required = false) List<String> symbols) {
        return transactionFacade.getAmount(symbols);
    }

    @DeleteMapping
    public ResponseEntity.BodyBuilder deleteTransactions() {
        transactionFacade.deleteTransactions();
        return ResponseEntity.accepted();
    }

    @Operation(summary = "Delete a transaction by ID", description = "Deletes a single transaction. Only deletes if it belongs to the authenticated user's portfolios.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "Transaction deleted", content = @Content),
        @ApiResponse(responseCode = "403", description = "Transaction does not belong to this user", content = @Content),
        @ApiResponse(responseCode = "404", description = "Transaction not found", content = @Content)
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(
            @Parameter(description = "Transaction ID", required = true) @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        transactionService.findById(id).ifPresentOrElse(tx -> {
            if (tx.getPortfolio() == null || !tx.getPortfolio().getUser().getId().equals(user.getId())) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "Transaction does not belong to this user");
            }
            transactionService.deleteById(id);
        }, () -> {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Transaction not found");
        });
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Deprecated
    public Page<Transaction> getAllTransactions(Pageable pageable) {
        return transactionService.getAll(pageable);
    }

    @Operation(summary = "Add a new transaction", description = "Manually add a new transaction to the system")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Transaction successfully created",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = Transaction.class))),
        @ApiResponse(responseCode = "400", description = "Invalid transaction data", content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Transaction addTransaction(
            @Parameter(description = "Transaction data", required = true) @RequestBody TransactionDto request) {
        return transactionFacade.save(request);
    }

    @Operation(summary = "Sync transactions from Binance",
            description = "Automatically fetch and sync transactions from Binance API. " +
                    "`portfolio` must be the dedicated Binance exchange portfolio (defaults to 'BINANCE' if omitted) — " +
                    "400 if it names a manually-managed portfolio or another exchange's portfolio.")
    @PostMapping("/sync/binance")
    public ResponseEntity<?> syncBinance(
            @AuthenticationPrincipal User user,
            @Parameter(description = "Portfolio name", required = true) @RequestParam String portfolio) {
        try {
            binanceSyncService.sync(user, portfolio);
            return ResponseEntity.ok("Sync initiated successfully");
        } catch (PortfolioNotExchangeOwnedException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Operation(summary = "Sync transactions from MexC",
            description = "Automatically fetch and sync transactions from MexC API. " +
                    "`portfolio` must be the dedicated MexC exchange portfolio (defaults to 'MEXC' if omitted) — " +
                    "400 if it names a manually-managed portfolio or another exchange's portfolio.")
    @PostMapping("/sync/mexc")
    public ResponseEntity<?> syncMexc(
            @AuthenticationPrincipal User user,
            @Parameter(description = "Portfolio name", required = true) @RequestParam String portfolio) {
        try {
            mexcSyncService.sync(user, portfolio);
            return ResponseEntity.ok("Sync initiated successfully");
        } catch (PortfolioNotExchangeOwnedException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Operation(summary = "Full historical sync from Binance (async, job-tracked)",
            description = "Creates one resumable sync job per data type (trades, deposits, withdrawals, fiat orders, " +
                    "convert trades) and runs each in the background. Returns 202 with the created jobs immediately; " +
                    "poll GET /transaction/sync/binance/jobs or subscribe to /user/queue/sync-status for progress. " +
                    "409 if a job of the same type is already running for this exchange config. " +
                    "startDate and endDate are epoch milliseconds (optional — defaults to 2017-01-01 to now).")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "202", description = "Jobs created and running",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = SyncJobResponse.class))),
        @ApiResponse(responseCode = "409", description = "A job of one of these types is already in progress", content = @Content)
    })
    @PostMapping("/sync/binance/full")
    public ResponseEntity<?> syncBinanceFull(
            @AuthenticationPrincipal User user,
            @Parameter(description = "Portfolio name", required = true) @RequestParam String portfolio,
            @Parameter(description = "Sync start date as epoch millis (optional, defaults to 2017-01-01)") @RequestParam(required = false) Long startDate,
            @Parameter(description = "Sync end date as epoch millis (optional, defaults to now)") @RequestParam(required = false) Long endDate) {
        try {
            List<SyncJob> jobs = binanceAsyncSyncService.triggerFullSyncJobs(user, portfolio, startDate, endDate);
            List<SyncJobResponse> response = jobs.stream()
                    .map(job -> SyncJobResponse.of(job, syncJobChunkRepository.findBySyncJobOrderByChunkKey(job)))
                    .collect(java.util.stream.Collectors.toList());
            return ResponseEntity.accepted().body(response);
        } catch (SyncJobAlreadyRunningException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (PortfolioNotExchangeOwnedException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Operation(summary = "List Binance sync jobs", description = "Most recent full-sync jobs for this user's Binance config, newest first.")
    @GetMapping("/sync/binance/jobs")
    public ResponseEntity<List<SyncJobResponse>> listBinanceSyncJobs(@AuthenticationPrincipal User user) {
        List<SyncJobResponse> response = syncJobRepository.findByUserAndExchangeNameOrderByCreatedAtDesc(user, ExchangeName.BINANCE).stream()
                .map(job -> SyncJobResponse.of(job, syncJobChunkRepository.findBySyncJobOrderByChunkKey(job)))
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get Binance sync job detail", description = "Job status plus every chunk (symbol or date window) and its state — use to see exactly which date range failed.")
    @GetMapping("/sync/binance/jobs/{jobId}")
    public ResponseEntity<?> getBinanceSyncJob(@AuthenticationPrincipal User user, @PathVariable UUID jobId) {
        return syncJobRepository.findById(jobId)
                .filter(job -> job.getUser().getId().equals(user.getId()))
                .map(job -> ResponseEntity.ok(SyncJobDetailResponse.of(job, syncJobChunkRepository.findBySyncJobOrderByChunkKey(job))))
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Retry a failed Binance sync job",
            description = "Only chunks (symbols/date windows) that failed are re-run — chunks already COMPLETED are left untouched. 400 if the job isn't currently FAILED.")
    @PostMapping("/sync/binance/jobs/{jobId}/retry")
    public ResponseEntity<?> retryBinanceSyncJob(@AuthenticationPrincipal User user, @PathVariable UUID jobId) {
        return syncJobRepository.findById(jobId)
                .filter(job -> job.getUser().getId().equals(user.getId()))
                .map(job -> {
                    if (job.getStatus() != SyncJobStatus.FAILED) {
                        return ResponseEntity.badRequest().body("Only a FAILED job can be retried (current status: " + job.getStatus() + ")");
                    }
                    binanceAsyncSyncService.retryJobAsync(jobId, user.getUsername());
                    return ResponseEntity.accepted().body("Retry started for job " + jobId);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Full historical sync from MexC (async)",
            description = "Enqueues a background full-history sync of all trades, deposits and withdrawals. " +
                    "Returns 202 immediately; a WebSocket message is sent to /user/queue/sync-status on completion.")
    @PostMapping("/sync/mexc/full")
    public ResponseEntity<?> syncMexcFull(
            @AuthenticationPrincipal User user,
            @Parameter(description = "Portfolio name", required = true) @RequestParam String portfolio,
            @Parameter(description = "Sync start date as epoch millis (optional, defaults to 2017-01-01)") @RequestParam(required = false) Long startDate,
            @Parameter(description = "Sync end date as epoch millis (optional, defaults to now)") @RequestParam(required = false) Long endDate) {
        mexcAsyncSyncService.syncFullHistoryAsync(user, portfolio, startDate, endDate);
        return ResponseEntity.accepted().body("Full historical MexC sync started. You will be notified upon completion.");
    }

    @Operation(summary = "Clear all transactions for a portfolio",
            description = "Permanently deletes all transactions belonging to the authenticated user's named portfolio.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "All transactions cleared", content = @Content),
        @ApiResponse(responseCode = "404", description = "Portfolio not found or not owned by user", content = @Content)
    })
    @DeleteMapping("/portfolio/{portfolioName}")
    public ResponseEntity<Void> clearPortfolioTransactions(
            @Parameter(description = "Portfolio name", required = true) @PathVariable String portfolioName,
            @AuthenticationPrincipal User user) {
        com.importer.fileimporter.entity.Portfolio portfolio =
                portfolioService.getByNameForUser(portfolioName, user)
                        .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Portfolio not found"));
        transactionService.deleteByPortfolio(portfolio);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Add a new transaction to a portfolio", description = "Manually add a new transaction to a specific portfolio")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Transaction successfully created",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = Transaction.class))),
        @ApiResponse(responseCode = "400", description = "Invalid transaction data", content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PostMapping("/{portfolio}")
    @ResponseStatus(HttpStatus.CREATED)
    public Transaction addTransactionToPortfolio(
            @Parameter(description = "Transaction data", required = true) @RequestBody TransactionDto request,
            @Parameter(description = "Portfolio name", required = true) @PathVariable String portfolio) {
        request.setPortfolioName(portfolio);
        return transactionFacade.save(request);
    }

    @Operation(summary = "Unprocess transactions", description = "Unprocess all transactions for a specific symbol and reset holdings")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Transactions successfully unprocessed",
                content = @Content(mediaType = "application/json")),
        @ApiResponse(responseCode = "400", description = "Invalid symbol", content = @Content),
        @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
        @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    })
    @PostMapping("/unprocess")
    public ResponseEntity<String> unprocessTransactions(
            @Parameter(description = "Symbol to unprocess transactions for", required = true) @RequestParam String symbol) {
        Map<String, Integer> stringIntegerHashMap = transactionFacade.unprocessTransactions(symbol);

        return ResponseEntity.ok(String.format("Successfully unprocessed %d transactions and reset %d holdings for symbol: %s",
                stringIntegerHashMap.get("transactions"), stringIntegerHashMap.get("holdings"), symbol));
    }
}
