package com.importer.fileimporter.facade;

import com.importer.fileimporter.converter.HoldingConverter;
import com.importer.fileimporter.dto.HoldingDto;
import com.importer.fileimporter.dto.PortfolioDistribution;
import com.importer.fileimporter.entity.Holding;
import com.importer.fileimporter.entity.Portfolio;
import com.importer.fileimporter.entity.Symbol;
import com.importer.fileimporter.payload.request.AddHoldingRequest;
import com.importer.fileimporter.service.HoldingService;
import com.importer.fileimporter.service.PortfolioService;
import com.importer.fileimporter.service.SymbolService;
import com.importer.fileimporter.service.TransactionService;
import com.importer.fileimporter.entity.Transaction;
import com.importer.fileimporter.utils.OperationUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.importer.fileimporter.utils.OperationUtils.BTC;
import static com.importer.fileimporter.utils.OperationUtils.USDT;

@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioDistributionFacade {

    private final SymbolService symbolService;
    private final PortfolioService portfolioService;
    private final HoldingService holdingService;
    private final PricingFacade pricingFacade;
    private final TransactionService transactionService;

    public List<String> getAllPortfolioNames() {
        List<Portfolio> portfolios = portfolioService.getAll();
        return portfolios.stream()
                .map(Portfolio::getName)
                .collect(Collectors.toList());
    }

    public Portfolio createPortfolio(String portfolioName) {
        return portfolioService.findOrSave(portfolioName);
    }

    public HoldingDto addPortfolioHolding(AddHoldingRequest request) {
        Symbol savedSymbol = symbolService.findOrSaveSymbol(request.getSymbol(), request.getName());

        Portfolio portfolio = portfolioService.findOrSave(request.getPortfolioName());

        return Optional.ofNullable(
                holdingService.saveSymbolHolding(savedSymbol, portfolio, request.getAmount()))
                .map(HoldingConverter.Mapper::createFrom)
                .orElse(null);
    }

    public HoldingDto getHolding(String portfolioName, String symbol) {
        Holding holding = holdingService.getBySymbolAndPortfolioName(portfolioName, symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Holding not found."));
        HoldingDto dto = HoldingConverter.Mapper.createFrom(holding);
        applyLiveValuation(dto, holding);
        return dto;
    }

    /**
     * currentPositionInUsdt/unrealizedProfitUsdt are always live-computed here (amount × current
     * price), never read off the persisted Holding — the entity's own currentPositionInUsdt drifts
     * over repeated recomputes (see CoinInformationService), and "current market value" has to use
     * *today's* price regardless of when the Holding row was last written anyway.
     */
    private void applyLiveValuation(HoldingDto dto, Holding holding) {
        Map<String, Double> price = pricingFacade.getPrices(holding.getSymbol());
        BigDecimal usdtPrice = Optional.ofNullable(price.get(USDT)).map(BigDecimal::valueOf).orElse(BigDecimal.ZERO);
        BigDecimal currentValueUsdt = usdtPrice.multiply(Optional.ofNullable(holding.getAmount()).orElse(BigDecimal.ZERO));
        BigDecimal costBasis = Optional.ofNullable(holding.getInventoryCostUsdt()).orElse(BigDecimal.ZERO);
        dto.setCurrentPositionInUsdt(currentValueUsdt);
        dto.setUnrealizedProfitUsdt(currentValueUsdt.subtract(costBasis));
    }

    private Optional<Portfolio> getByName(String portfolioName) {
        return portfolioService.getByName(portfolioName);
    }

    public PortfolioDistribution getPortfolioByName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing param.");
        }
        if (Objects.equals(name.toLowerCase(Locale.ROOT), "all")) {
            return calculateAllPortfolioInBtcAndUsdt();
        }
        Portfolio portfolio = getByName(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Portfolio not found."));
        return calculatePortfolioInBtcAndUsdt(portfolio);
    }

    @Transactional
    public PortfolioDistribution calculatePortfolioInBtcAndUsdt(String name) {
        if (!StringUtils.hasText(name)) {
            return calculatePortfolioInBtcAndUsdt();
        }
        if (Objects.equals(name.toLowerCase(Locale.ROOT), "all")) {
            return calculateAllPortfolioInBtcAndUsdt();
        }
        Portfolio portfolio = portfolioService.getByName(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Portfolio not found"));
        
        List<Holding> holdings = holdingService.getByPortfolio(portfolio);
        if (holdings == null || holdings.isEmpty()) {
            return PortfolioDistribution.builder()
                    .portfolioName(portfolio.getName())
                    .exchangeName(portfolio.getExchangeName())
                    .holdings(new ArrayList<>())
                    .totalUsdt(BigDecimal.ZERO)
                    .build();
        }
        return calculatePortfolioInBtcAndUsdt(portfolio);
    }

    private PortfolioDistribution calculateAllPortfolioInBtcAndUsdt() {
        List<Portfolio> portfolios = portfolioService.getAll();
        return getPortfolios(portfolios);
    }

    @Transactional
    public PortfolioDistribution calculatePortfolioInBtcAndUsdt() {
        List<Portfolio> portfolios = portfolioService.findAll();
        final List<PortfolioDistribution> collect = portfolios.stream()
                .map(this::calculatePortfolioInBtcAndUsdt)
                .collect(Collectors.toList());
        if (collect.isEmpty()) {
            return PortfolioDistribution.builder()
                    .portfolioName("Default")
                    .holdings(new ArrayList<>())
                    .totalUsdt(BigDecimal.ZERO)
                    .build();
        }
        return collect.stream().findFirst().get();
    }

    public PortfolioDistribution calculatePortfolioInBtcAndUsdt(Portfolio portfolio) {
        PortfolioDistribution portfolioDistribution = PortfolioDistribution.builder()
                .portfolioName(portfolio.getName())
                .exchangeName(portfolio.getExchangeName())
                .holdings(new ArrayList<>())
                .totalBuySpentUsdt(BigDecimal.ZERO)
                .totalSellEarnedUsdt(BigDecimal.ZERO)
                .build();

        addHoldingsToPortfolioDistribution(portfolio, portfolioDistribution);
        addTransactionTotalsToPortfolioDistribution(portfolio, portfolioDistribution);

        BigDecimal totalUsdt = portfolioDistribution.getTotalInUsdt();
        portfolioDistribution.setTotalUsdt(totalUsdt);
        if (totalUsdt != null && totalUsdt.compareTo(BigDecimal.ZERO) > 0) {
            portfolioDistribution.getHoldings().forEach(
                    e -> {
                        e.setPercentage(e.getAmountInUsdt()
                                .divide(totalUsdt, 7, RoundingMode.DOWN)
                                .multiply(new BigDecimal(100)));
                        updateHolding(e, portfolio);
                    }
            );
        }
        return portfolioDistribution;
    }

    private void addHoldingsToPortfolioDistribution(Portfolio portfolio, PortfolioDistribution portfolioDistribution) {
        List<Holding> holdings = portfolio.getHoldings();
        if (holdings == null) {
            holdings = holdingService.getByPortfolio(portfolio);
        }
        holdings.stream()
                .filter(excludeWhenAmountIsAlmostZero())
                .forEach(e -> {
                    Map<String, Double> price = pricingFacade.getPrices(e.getSymbol());
                    BigDecimal btcprice =
                            Optional.ofNullable(price.get(BTC)).map(BigDecimal::valueOf).orElse(BigDecimal.ZERO);
                    BigDecimal usdtprice =
                            Optional.ofNullable(price.get(USDT)).map(BigDecimal::valueOf).orElse(BigDecimal.ZERO);
                    BigDecimal currentPositionInUsdt = usdtprice.multiply(e.getAmount());
                    BigDecimal costBasis = e.getInventoryCostUsdt() != null ? e.getInventoryCostUsdt() : BigDecimal.ZERO;
                    BigDecimal unrealizedProfitUsdt = currentPositionInUsdt.subtract(costBasis);

                    // TODO: use converter to create this HoldingDto.
                    portfolioDistribution.getHoldings().add(HoldingDto.builder()
                            .symbol(e.getSymbol())
                            .amount(e.getAmount())
                            .priceInBtc(btcprice)
                            .priceInUsdt(usdtprice)
                            .amountInBtc(BTC.equals(e.getSymbol()) ? e.getAmount() :
                                    btcprice.multiply(e.getAmount()))
                            .amountInUsdt(USDT.equals(e.getSymbol()) ? e.getAmount() :
                                    usdtprice.multiply(e.getAmount()))
                            .totalAmountBought(e.getTotalAmountBought())
                            .totalAmountSold(e.getTotalAmountSold())
                            .totalRealizedProfitUsdt(e.getTotalRealizedProfitUsdt())
                            .inventoryCostUsdt(e.getInventoryCostUsdt())
                            .currentPositionInUsdt(currentPositionInUsdt)
                            .unrealizedProfitUsdt(unrealizedProfitUsdt)
                            .build());
                }
        );
    }

    private void addTransactionTotalsToPortfolioDistribution(
            Portfolio portfolio,
            PortfolioDistribution portfolioDistribution
    ) {
        List<Transaction> transactions = transactionService.findByPortfolio(portfolio);

        BigDecimal totalBuySpentUsdt = transactions.stream()
                .filter(transaction -> OperationUtils.isBuy(transaction.getSide()))
                .map(this::getTransactionValueInUsdt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalSellEarnedUsdt = transactions.stream()
                .filter(transaction -> OperationUtils.SELL_STRING.equals(transaction.getSide()))
                .map(this::getTransactionValueInUsdt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        portfolioDistribution.setTotalBuySpentUsdt(totalBuySpentUsdt);
        portfolioDistribution.setTotalSellEarnedUsdt(totalSellEarnedUsdt);

        transactions.stream()
                .map(Transaction::getDateUtc)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .ifPresent(portfolioDistribution::setOldestTransactionDate);

        transactions.stream()
                .map(Transaction::getDateUtc)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .ifPresent(portfolioDistribution::setNewestTransactionDate);
    }

    private BigDecimal getTransactionValueInUsdt(Transaction transaction) {
        if (transaction == null || transaction.getExecuted() == null) {
            return BigDecimal.ZERO;
        }

        if (OperationUtils.isStable(transaction.getPaidWith()) && transaction.getPaidAmount() != null) {
            return transaction.getPaidAmount();
        }

        BigDecimal resolvedPrice = pricingFacade.getPriceInUsdt(transaction.getSymbol(), transaction.getDateUtc());
        if (resolvedPrice == null || resolvedPrice.compareTo(BigDecimal.ZERO) <= 0) {
            resolvedPrice = transaction.getPrice() != null ? transaction.getPrice() : BigDecimal.ZERO;
        }

        return transaction.getExecuted().multiply(resolvedPrice);
    }

    private Predicate<Holding> excludeWhenAmountIsAlmostZero() {
        return holding -> {
            BigDecimal amount = Optional.ofNullable(holding.getAmount()).orElse(BigDecimal.ZERO);
            return amount.abs().compareTo(new BigDecimal("0.00000001")) > 0;
        };
    }

    private HoldingDto updateHolding(HoldingDto e, Portfolio portfolio) {
        return holdingService.updatePercentageHolding(e, portfolio);
    }

    private PortfolioDistribution getPortfolios(List<Portfolio> portfolios) {
        return getPortfoliosWithHoldings(portfolios.stream()
                .flatMap(e -> e.getHoldings().stream())
                .collect(Collectors.toList()));
    }

    public PortfolioDistribution getPortfoliosWithHoldings(List<Holding> holdings) {
        PortfolioDistribution portfolioDistribution = PortfolioDistribution.builder()
                .portfolioName("All")
                .holdings(new ArrayList<>())
                .build();

        Map<String, HoldingDto> holdingsBySymbol = groupHoldingsBySymbol(holdings);
        List<HoldingDto> holdingDtos = new ArrayList<>(holdingsBySymbol.values());
        portfolioDistribution.setHoldings(holdingDtos);
        portfolioDistribution.calculateHoldingPercent();
        holdingDtos.sort((Comparator.comparing(HoldingDto::getAmountInUsdt, Comparator.nullsLast(Comparator.reverseOrder()))));
        return portfolioDistribution;
    }

    /**
     * Groups holdings by symbol and merges their values.
     * This method is used to aggregate holdings across different portfolios.
     *
     * @param holdings The list of holdings to group
     * @return A map of symbol to aggregated HoldingDto
     */
    public Map<String, HoldingDto> groupHoldingsBySymbol(List<Holding> holdings) {
        return holdings.stream()
                .collect(Collectors.toMap(Holding::getSymbol, // Group holdings by symbol
                        HoldingConverter.Mapper::createFrom,
                        (e1, e2) -> {
                            log.info("Merging holdings - Holding1:" + e1.getSymbol() + " - Holding2:" + e2.getSymbol());

                            // Calculate prices (average of the two holdings)
                            BigDecimal priceInUsdt = (e1.getPriceInUsdt() != null && e2.getPriceInUsdt() != null) ?
                                    e1.getPriceInUsdt().add(e2.getPriceInUsdt()).divide(new BigDecimal(2), 8, RoundingMode.HALF_UP) :
                                    (e1.getPriceInUsdt() != null ? e1.getPriceInUsdt() : e2.getPriceInUsdt());

                            BigDecimal priceInBtc = (e1.getPriceInBtc() != null && e2.getPriceInBtc() != null) ?
                                    e1.getPriceInBtc().add(e2.getPriceInBtc()).divide(new BigDecimal(2), 8, RoundingMode.HALF_UP) :
                                    (e1.getPriceInBtc() != null ? e1.getPriceInBtc() : e2.getPriceInBtc());

                            BigDecimal percentage = (e1.getPercentage() != null && e2.getPercentage() != null) ?
                                    e1.getPercentage().add(e2.getPercentage()) : 
                                    (e1.getPercentage() != null ? e1.getPercentage() : e2.getPercentage());

                            // Build the merged holding with all fields properly aggregated
                            return HoldingDto.builder()
                                    .symbol(e1.getSymbol())
                                    .portfolioName(e1.getPortfolioName() + " - " + e2.getPortfolioName())
                                    .priceInUsdt(priceInUsdt)
                                    .priceInBtc(priceInBtc)
                                    .amount(addPreventingNull(e1.getAmount(), e2.getAmount()))
                                    .amountInUsdt(addPreventingNull(e1.getAmountInUsdt(), e2.getAmountInUsdt()))
                                    .amountInBtc(addPreventingNull(e1.getAmountInBtc(), e2.getAmountInBtc()))
                                    .percentage(percentage)
                                    // Include transaction history metrics
                                    .totalAmountBought(addPreventingNull(e1.getTotalAmountBought(), e2.getTotalAmountBought()))
                                    .totalAmountSold(addPreventingNull(e1.getTotalAmountSold(), e2.getTotalAmountSold()))
                                    .inventoryCostUsdt(addPreventingNull(e1.getInventoryCostUsdt(), e2.getInventoryCostUsdt()))
                                    .currentPositionInUsdt(addPreventingNull(e1.getCurrentPositionInUsdt(), e2.getCurrentPositionInUsdt()))
                                    .totalRealizedProfitUsdt(addPreventingNull(e1.getTotalRealizedProfitUsdt(), e2.getTotalRealizedProfitUsdt()))
                                    .unrealizedProfitUsdt(addPreventingNull(e1.getUnrealizedProfitUsdt(), e2.getUnrealizedProfitUsdt()))
                                    .build();
                        }
                ));
    }

    public BigDecimal addPreventingNull(BigDecimal amount1, BigDecimal amount2) {
        BigDecimal bigDecimal1 = Optional.ofNullable(amount1).orElse(BigDecimal.ZERO);
        BigDecimal bigDecimal2 = Optional.ofNullable(amount2).orElse(BigDecimal.ZERO);
        return bigDecimal1.add(bigDecimal2).setScale(0, RoundingMode.DOWN);
    }

    public ResponseEntity<byte[]> downloadExcel() {
        List<Portfolio> portfolios = portfolioService.findAll();
        // Sample list of HoldingDto (Replace this with your actual data)
        PortfolioDistribution portfoliosDistribution = getPortfolios(portfolios);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             Workbook workbook = createWorkbook(portfoliosDistribution)) {

            workbook.write(outputStream);
            byte[] excelBytes = outputStream.toByteArray();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", "portfolio_distribution.xlsx");

            return new ResponseEntity<>(excelBytes, headers, HttpStatus.OK);
        } catch (IOException e) {
            e.printStackTrace();
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private Workbook createWorkbook(PortfolioDistribution portfolios) {
        Workbook workbook = new HSSFWorkbook();
        Sheet sheet = workbook.createSheet("Portfolio Distribution");

        // Create header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Symbol", "Portfolio Name", "Amount", "Amount in BTC", "Amount in USDT", "Percentage"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }

        // Populate data rows
        int rowNum = 1;
        for (HoldingDto holding : portfolios.getHoldings()) {
            Row row = sheet.createRow(rowNum++);
            BigDecimal getAmountInBtc = getGetAmount(holding.getAmountInBtc());
            BigDecimal amount = getGetAmount(holding.getAmount());
            BigDecimal getAmountInUsdt = getGetAmount(holding.getAmountInUsdt());
            row.createCell(0).setCellValue(holding.getSymbol());
            row.createCell(1).setCellValue(holding.getPortfolioName());
            row.createCell(2).setCellValue(amount.doubleValue());
            row.createCell(3).setCellValue(getAmountInBtc.doubleValue());
            row.createCell(4).setCellValue(getAmountInUsdt.doubleValue());
            row.createCell(5).setCellValue(holding.getPercentage().doubleValue());
        }

        // Autosize columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        return workbook;
    }

    private BigDecimal getGetAmount(BigDecimal holding) {
        return Optional.ofNullable(holding)
                .filter(Objects::nonNull)
                .orElse(BigDecimal.ZERO);
    }

    public List<HoldingDto> addPortfolioHolding(List<AddHoldingRequest> requests) {
        Optional<Portfolio> portfolio = portfolioService.getByName(requests.get(0).getPortfolioName());
        return HoldingConverter.Mapper.createFromEntities(holdingService.saveAll(portfolio.get(), requests));
    }
}
