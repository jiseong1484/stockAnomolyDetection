package com.stock.anomaly.application.stock;

import com.stock.anomaly.domain.user.User;
import com.stock.anomaly.infrastructure.external.kis.KisApiService;
import com.stock.anomaly.infrastructure.persistence.influxdb.InfluxDbRepository;
import com.stock.anomaly.web.user.dto.OhlcvResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final InfluxDbRepository influxDbRepository;
    private final KisApiService kisApiService;

    public List<OhlcvResponse> getOhlcv(String ticker, String interval, int days, String endDate, User user) {
        log.info("[StockService] getOhlcv request: ticker={}, interval={}, days={}, endDate={}", ticker, interval, days, endDate);
        List<OhlcvResponse> dbData;
        java.time.Instant start = null;
        java.time.Instant end = null;

        // 1. 요청 범위 설정
        if (endDate != null) {
            java.time.LocalDate endLocalDate;
            if (endDate.length() == 8) {
                endLocalDate = java.time.LocalDate.parse(endDate, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            } else {
                // yyyyMMddHHmmss 형식 처리
                endLocalDate = java.time.LocalDate.parse(endDate.substring(0, 8), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            }
            end = endLocalDate.atTime(23, 59, 59).toInstant(java.time.ZoneOffset.UTC);
            start = endLocalDate.minusDays(days).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
            dbData = influxDbRepository.findOhlcv(ticker, interval, start, end);
        } else {
            dbData = influxDbRepository.findOhlcv(ticker, interval, days);
        }

        log.info("[StockService] DB data retrieved: size={}", dbData.size());

        // 2. KIS API에서 과거 데이터 조회 (DB에 데이터가 충분하지 않은 경우)
        boolean needsApiFetch = endDate != null ? dbData.size() < 10 : dbData.size() < 50;
        log.info("[StockService] needsApiFetch={}, userHasKeys={}", needsApiFetch, user.getKisApiKey() != null);

        if (needsApiFetch) {
            if (user.getKisApiKey() != null && user.getKisSecretKey() != null) {
                log.info("[StockService] Fetching from KIS API...");
                List<OhlcvResponse> apiData = kisApiService.fetchHistoricalOhlcv(
                        ticker, interval, days, endDate, user.getKisApiKey(), user.getKisSecretKey());

                log.info("[StockService] KIS API response size: {}", apiData.size());

                if (!apiData.isEmpty()) {
                    // API 데이터를 DB에 캐싱
                    influxDbRepository.saveOhlcvList(ticker, apiData);
                    log.info("[StockService] Cached {} points to InfluxDB", apiData.size());

                    // 병합 (중복 제거를 위해 Map 사용)
                    java.util.Map<Long, OhlcvResponse> mergedMap = new java.util.TreeMap<>();
                    for (OhlcvResponse data : apiData) mergedMap.put(data.getTime(), data);
                    for (OhlcvResponse data : dbData) mergedMap.put(data.getTime(), data);

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
