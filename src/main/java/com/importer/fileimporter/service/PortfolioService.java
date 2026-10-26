package com.importer.fileimporter.service;

import com.importer.fileimporter.config.security.services.CurrentUserProvider;
import com.importer.fileimporter.entity.ExchangeName;
import com.importer.fileimporter.entity.Portfolio;
import com.importer.fileimporter.entity.Transaction;
import com.importer.fileimporter.entity.User;
import com.importer.fileimporter.repository.PortfolioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Service
@Slf4j
public class PortfolioService {

    private final PortfolioRepository portfolioRepository;
    private final CurrentUserProvider currentUserProvider;
    private final TransactionService transactionService;

    public Portfolio findOrSave(String name) {
        return findOrSave(name, null);
    }

    public Portfolio findOrSave(String name, ExchangeName exchangeName) {
        log.info("Finding or saving portfolio: " + name);
        User currentUser = currentUserProvider.getCurrentUser();
        Optional<Portfolio> byName;
        
        if (currentUser != null) {
            byName = getByNameForUser(name, currentUser);
        } else {
            byName = getByName(name);
        }
        
        Portfolio result = byName.orElseGet(() -> saveBasicEntity(name, exchangeName));
        if (exchangeName != null && result.getExchangeName() == null) {
            result.setExchangeName(exchangeName);
            result = portfolioRepository.save(result);
        }
        log.info("findOrSave result for {}: {}", name, result.getId());
        return result;
    }

    /**
     * Resolves (or auto-creates) the portfolio an exchange sync should write into, and refuses
     * to let a sync silently take over a manually-managed portfolio or another exchange's
     * portfolio. Every exchange sync entry point (Binance/MexC, incremental and full) must call
     * this instead of {@link #findOrSave}, which has no such guard.
     *
     * @param requestedName the {@code portfolio} param the caller passed (may be null/blank —
     *                       defaults to the exchange's own name, e.g. "BINANCE")
     */
    public Portfolio resolveExchangePortfolio(String requestedName, ExchangeName exchangeName) {
        String name = (requestedName == null || requestedName.isBlank()) ? exchangeName.name() : requestedName;
        User currentUser = currentUserProvider.getCurrentUser();
        Optional<Portfolio> existing = currentUser != null ? getByNameForUser(name, currentUser) : getByName(name);

        if (existing.isEmpty()) {
            return saveBasicEntity(name, exchangeName);
        }
        Portfolio portfolio = existing.get();
        if (portfolio.getExchangeName() != exchangeName) {
            throw new PortfolioNotExchangeOwnedException(name, exchangeName);
        }
        return portfolio;
    }

    /**
     * Moves every transaction from an exchange-owned portfolio into a manually-managed one —
     * the explicit "I've reviewed the synced data, merge it into my portfolio" action. Only
     * exchange -> manual is supported (never manual -> manual or exchange -> exchange); running
     * it again after a later sync is safe, it just moves whatever hasn't been moved yet.
     *
     * @return {@code {movedCount, skippedCount}} — skipped rows are ones that would collide with
     *         a transaction already consolidated into the target (same exchange + externalId),
     *         left untouched on the source rather than silently dropped.
     */
    public int[] consolidate(String sourcePortfolioName, String targetPortfolioName, User user) {
        Portfolio source = getByNameForUser(sourcePortfolioName, user)
                .orElseThrow(() -> new IllegalArgumentException("Source portfolio not found: " + sourcePortfolioName));
        Portfolio target = getByNameForUser(targetPortfolioName, user)
                .orElseThrow(() -> new IllegalArgumentException("Target portfolio not found: " + targetPortfolioName));

        if (source.getExchangeName() == null) {
            throw new IllegalArgumentException("Source portfolio '" + sourcePortfolioName + "' isn't an exchange portfolio");
        }
        if (target.getExchangeName() != null) {
            throw new IllegalArgumentException("Target portfolio '" + targetPortfolioName + "' is itself an exchange portfolio; consolidate only merges exchange data into a manually-managed portfolio");
        }

        List<Transaction> toMove = transactionService.findByPortfolio(source);
        int moved = 0;
        int skipped = 0;
        for (Transaction tx : toMove) {
            tx.setPortfolio(target);
            try {
                transactionService.save(tx);
                moved++;
            } catch (DataIntegrityViolationException e) {
                log.debug("Skipping transaction {} during consolidate — already present on target {}: {}",
                        tx.getId(), targetPortfolioName, e.getMessage());
                skipped++;
            }
        }
        log.info("Consolidated {} -> {}: moved {}, skipped {} (already on target)",
                sourcePortfolioName, targetPortfolioName, moved, skipped);
        return new int[]{moved, skipped};
    }

    public Optional<Portfolio> getByName(String name) {
        List<Portfolio> allByName = portfolioRepository.findAllByName(name);
        if (allByName.size() > 1) {
            log.warn("Multiple portfolios found with name: {}. IDs: {}", name, 
                    allByName.stream().map(p -> p.getId().toString()).collect(java.util.stream.Collectors.joining(", ")));
        }
        return allByName.isEmpty() ? Optional.empty() : Optional.of(allByName.get(0));
    }

    public List<Portfolio> getAll() {
        return portfolioRepository.findAll();
    }

    public List<Portfolio> getAllForUser(User user) {
        return portfolioRepository.findByUser(user);
    }

    public Optional<Portfolio> getByNameForUser(String name, User user) {
        return portfolioRepository.findByNameAndUser(name, user);
    }

    private Portfolio saveBasicEntity(String name, ExchangeName exchangeName) {
        log.info("New portfolio detected: " + name);
        User currentUser = currentUserProvider.getCurrentUser();

        Portfolio portfolio = Portfolio.builder()
                .name(name)
                .exchangeName(exchangeName)
                .creationDate(LocalDateTime.now())
                .user(currentUser)
                .build();

        if (currentUser != null) {
            log.info("Associating portfolio with user: " + currentUser.getUsername());
        } else {
            log.warn("No authenticated user found when creating portfolio: " + name);
        }

        return portfolioRepository.save(portfolio);
    }

    public List<Portfolio> findAll() {
        return portfolioRepository.findAll();
    }
}
