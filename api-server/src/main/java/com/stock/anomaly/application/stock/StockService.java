package com.stock.anomaly.application.stock;

import com.stock.anomaly.domain.stock.Stock;
import com.stock.anomaly.domain.stock.StockRepository;
import com.stock.anomaly.domain.user.User;
import com.stock.anomaly.domain.user.UserRepository;
import com.stock.anomaly.infrastructure.external.kis.KisApiService;
import com.stock.anomaly.infrastructure.persistence.influxdb.InfluxDbRepository;
import com.stock.anomaly.web.user.dto.OhlcvResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final InfluxDbRepository influxDbRepository;
    private final KisApiService kisApiService;
    private final StockRepository stockRepository;
    private final UserRepository userRepository;

    private static final int STOCK_MASTER_THRESHOLD = 50;

    @EventListener(ApplicationReadyEvent.class)
    public void syncStockMasterDataOnStartup() {
        if (stockRepository.count() < STOCK_MASTER_THRESHOLD) {
            loadFullStockMaster();
        } else {
            log.info("[StockService] Stock master already loaded, skipping.");
        }
    }

    @Transactional
    public List<Stock> searchStocks(String query, User user) {
        log.info("[StockService] Searching stocks for query: '{}' (User: {})", query, user.getEmail());

        // 1. DB 검색
        List<Stock> localResults;
        if (query.matches("^[0-9]+$")) {
            localResults = stockRepository.findByTickerStartingWith(query);
        } else {
            localResults = stockRepository.findByNameContaining(query);
        }

        log.info("[StockService] Found {} matches in local DB", localResults.size());

        if (!localResults.isEmpty()) {
            return localResults;
        }

        // 2. 6자리 코드로 검색 시 KIS API 단일 조회
        if (query.matches("^[0-9]{6}$")) {
            log.info("[StockService] No local result for ticker {}. Fetching from KIS...", query);
            List<Map<String, String>> remoteResults = kisApiService.searchStockByName(query, user.getKisApiKey(), user.getKisSecretKey());
            for (Map<String, String> item : remoteResults) {
                String ticker = item.get("pdno");
                String name = item.get("prdt_abrv_name");
                if (ticker != null && name != null) {
                    Stock stock = stockRepository.findById(ticker).orElseGet(() ->
                            stockRepository.save(Stock.builder().ticker(ticker).name(name).market("KOSPI").build())
                    );
                    localResults.add(stock);
                }
            }
        }

        return localResults;
    }

    private void loadFullStockMaster() {
        try {
            log.info("[StockService] Loading full stock master from KRX...");
            List<Map<String, String>> allStocks = kisApiService.fetchAllListedStocks();

            if (allStocks.isEmpty()) {
                log.warn("[StockService] KRX returned no stocks.");
                return;
            }

            Set<String> existingTickers = stockRepository.findAllTickers();
            List<Stock> toSave = allStocks.stream()
                    .filter(item -> item.get("ticker") != null && !existingTickers.contains(item.get("ticker")))
                    .map(item -> Stock.builder()
                            .ticker(item.get("ticker"))
                            .name(item.get("name"))
                            .market(item.get("market"))
                            .build())
                    .collect(Collectors.toList());

            stockRepository.saveAll(toSave);
            log.info("[StockService] Stock master loaded: {} stocks saved", toSave.size());
        } catch (Exception e) {
            log.error("[StockService] Failed to load stock master: {}", e.getMessage());
        }
    }

    public List<OhlcvResponse> getOhlcv(String ticker, String interval, int days, String endDate, User user) {
        log.info("[StockService] getOhlcv request: ticker={}, interval={}, days={}, endDate={}", ticker, interval, days, endDate);
        List<OhlcvResponse> dbData;
        java.time.Instant start = null;
        java.time.Instant end = null;

        // 분봉은 InfluxDB 쿼리 범위를 최대 3일로 제한 (수십만 레코드 타임아웃 방지)
        int effectiveDays = "1m".equals(interval) ? Math.min(days, 3) : days;

        java.time.ZoneOffset KST = java.time.ZoneOffset.of("+09:00");

        // 1. 요청 범위 설정
        if (endDate != null) {
            if (endDate.length() >= 14) {
                // 분봉: yyyyMMddHHmmss 형식 KST → UTC Instant
                // 시간 정보를 살려야 이미 로드된 범위 밖의 데이터를 쿼리할 수 있음
                java.time.LocalDateTime endKst = java.time.LocalDateTime.parse(
                        endDate.substring(0, 14), java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                end = endKst.toInstant(KST);
                start = endKst.minusDays(effectiveDays).toInstant(KST);
            } else {
                java.time.LocalDate endLocalDate = java.time.LocalDate.parse(
                        endDate, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
                end = endLocalDate.atTime(23, 59, 59).toInstant(java.time.ZoneOffset.UTC);
                start = endLocalDate.minusDays(effectiveDays).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
            }
            dbData = influxDbRepository.findOhlcv(ticker, interval, start, end);
        } else {
            dbData = influxDbRepository.findOhlcv(ticker, interval, effectiveDays);
        }

        log.info("[StockService] DB data retrieved: size={}", dbData.size());

        // 2. KIS API에서 과거 데이터 조회 (DB에 데이터가 충분하지 않은 경우)
        // 분봉은 KIS API가 1회 호출당 ~30개만 반환하므로 임계값을 낮게 설정
        int fetchThreshold = "1m".equals(interval) ? 20 : 50;
        boolean needsApiFetch = endDate != null ? dbData.size() < 10 : dbData.size() < fetchThreshold;
        log.info("[StockService] needsApiFetch={}, userHasKeys={}", needsApiFetch, user.getKisApiKey() != null);

        if (needsApiFetch) {
            if (user.getKisApiKey() != null && user.getKisSecretKey() != null) {
                log.info("[StockService] Fetching from KIS API...");
                List<OhlcvResponse> apiData = kisApiService.fetchHistoricalOhlcv(
                        ticker, interval, days, endDate, user.getKisApiKey(), user.getKisSecretKey());

                log.info("[StockService] KIS API response size: {}", apiData.size());

                if (!apiData.isEmpty()) {
                    // API 데이터를 DB에 캐싱
                    influxDbRepository.saveOhlcvList(ticker, interval, apiData);
                    log.info("[StockService] Cached {} points to InfluxDB", apiData.size());

                    // 병합 (중복 제거를 위해 Map 사용) — KIS API 데이터가 캐시를 덮어써야 최신값 유지
                    java.util.Map<Long, OhlcvResponse> mergedMap = new java.util.TreeMap<>();
                    for (OhlcvResponse data : dbData) mergedMap.put(data.getTime(), data);
                    for (OhlcvResponse data : apiData) mergedMap.put(data.getTime(), data);

                    List<OhlcvResponse> result = new java.util.ArrayList<>(mergedMap.values());
                    log.info("[StockService] Merged result size: {}", result.size());

                    if (endDate != null && end != null) {
                        long finalEndSec = end.getEpochSecond();
                        return result.stream().filter(d -> d.getTime() <= finalEndSec).collect(java.util.stream.Collectors.toList());
                    }
                    return result;
                }
            } else {
                log.warn("[StockService] KIS API keys missing for user: {}", user.getEmail());
            }
        }

        return dbData;
    }}
