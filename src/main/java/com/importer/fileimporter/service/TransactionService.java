package com.importer.fileimporter.service;

import com.importer.fileimporter.entity.Portfolio;
import com.importer.fileimporter.entity.Transaction;
import com.importer.fileimporter.repository.TransactionRepository;
import com.importer.fileimporter.specification.TransactionSpecifications;
import com.importer.fileimporter.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@Service
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public Page<Transaction> filterTransactions(String symbol, String portfolioName, String side,
                                                String paidWith, String paidAmountOperator, BigDecimal paidAmount,
                                                LocalDate startDate, LocalDate endDate,
                                                UUID userId, Pageable pageable) {

        log.info("Filtering transactions with parameters: symbol={}, portfolioName={}, side={}, paidWith={}, " +
                        "paidAmountOperator={}, paidAmount={}, startDate={}, endDate={}",
                symbol, portfolioName, side, paidWith, paidAmountOperator, paidAmount, startDate, endDate);

        Specification<Transaction> specification = TransactionSpecifications.getSpecWithFilters(symbol, portfolioName, side,
                paidWith, paidAmountOperator, paidAmount, startDate, endDate, userId);
        Page<Transaction> result = transactionRepository.findAll(specification, pageable);
        log.info("Found {} transactions", result.getTotalElements());

        return result;
    }

    public Page<Transaction> getAllBySymbol(String symbol, Pageable pageable) {
        return transactionRepository.findAllBySymbol(symbol, pageable);
    }

    public List<Transaction> getAllBySymbol(String symbol) {
        return transactionRepository.findAllBySymbol(symbol);
    }

    public List<Transaction> getAll() {
        return transactionRepository.findAll();
    }

    public Page<Transaction> getAll(Pageable pageable) {
        return transactionRepository.findAll(pageable);
    }

    public void saveTransaction(String coinName,
                                String symbolPair, String date,
                                String pair, String side,
                                BigDecimal price, BigDecimal executed,
                                BigDecimal amount, BigDecimal fee, String origin) {
        saveTransaction(coinName, symbolPair, date, pair, side, price, executed, amount, fee, origin, null);
    }

    public Transaction saveTransaction(String coinName,
                                       String symbolPair, String date,
                                       String pair, String side,
                                       BigDecimal price, BigDecimal executed,
                                       BigDecimal amount, BigDecimal fee, String origin,
                                       Portfolio portfolio) {
        LocalDateTime dateTime = DateUtils.getLocalDateTime(date);

        Transaction transaction = Transaction.builder()
                .dateUtc(dateTime)
                .pair(pair)
                .executed(executed)
                .side(side)
                .price(price)
                .symbol(coinName)
                .paidWith(symbolPair)
                .paidAmount(amount)
                .created(LocalDateTime.now())
                .createdBy(origin)
                .feeAmount(fee)
                .modified(LocalDateTime.now())
                .modifiedBy(origin)
                .portfolio(portfolio)
                .build();

        return save(transaction);
    }

    public Transaction save(Transaction transaction) {
        return transactionRepository.save(transaction);
    }

    public java.util.Optional<Transaction> saveIfAbsent(Transaction transaction) {
        if (transaction.getExternalId() != null && transaction.getPortfolio() != null) {
            java.util.Optional<Transaction> existing = transactionRepository.findByPortfolioAndExchangeNameAndExternalId(
                    transaction.getPortfolio(), transaction.getExchangeName(), transaction.getExternalId());
            if (existing.isPresent()) {
                log.debug("Skipping duplicate transaction: {} {} (External ID: {})",
                        transaction.getExchangeName(), transaction.getSymbol(), transaction.getExternalId());
                return java.util.Optional.empty();
            }
        }
        try {
            return java.util.Optional.of(transactionRepository.save(transaction));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // The check above isn't race-safe against concurrent/overlapping sync retries of the
            // same window — the DB's uk_portfolio_exchange_extid constraint is the real guarantee.
            // Treat a constraint violation here the same as an already-existing row: a clean skip,
            // not a 500 surfaced to the caller.
            log.debug("Concurrent duplicate insert caught for {} {} (External ID: {}): {}",
                    transaction.getExchangeName(), transaction.getSymbol(), transaction.getExternalId(), e.getMessage());
            return java.util.Optional.empty();
        }
    }

    public void flush() {
        transactionRepository.flush();
    }

    public void deleteTransactions() {
        transactionRepository.deleteAll();
    }

    public void deleteById(Long id) {
        transactionRepository.deleteById(id);
    }

    public java.util.Optional<Transaction> findById(Long id) {
        return transactionRepository.findById(id);
    }

    public List<Transaction> findByPortfolio(Portfolio portfolio) {
        return transactionRepository.findAllByPortfolio(portfolio);
    }

    public List<Transaction> findByPortfolioAndSymbol(Portfolio portfolio, String symbol) {
        return transactionRepository.findAllByPortfolioAndSymbol(portfolio, symbol);
    }

    @javax.transaction.Transactional
    public void deleteByPortfolio(Portfolio portfolio) {
        transactionRepository.deleteAllByPortfolio(portfolio);
    }

    public int unprocessTransactionsBySymbol(String symbol) {
        List<Transaction> transactions = getAllBySymbol(symbol);
        int count = 0;

        for (Transaction transaction : transactions) {
            if (transaction.isProcessed()) {
                transaction.setProcessed(false);
                transaction.setLastProcessedAt(null);
                transaction.setModified(LocalDateTime.now());
                transaction.setModifiedBy(this.getClass().getName() + ".unprocessTransactionsBySymbol");
                save(transaction);
                count++;
            }
        }

        log.info("Unprocessed {} transactions for symbol: {}", count, symbol);
        return count;
    }
}
