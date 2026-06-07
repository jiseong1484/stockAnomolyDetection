package com.stock.anomaly.infrastructure.external.kis;

import com.stock.anomaly.web.user.dto.OhlcvResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisApiService {

    @Value("${kis.api.url}")
    private String apiUrl;

    private final KisTokenManager kisTokenManager;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final java.time.ZoneOffset KST_OFFSET = java.time.ZoneOffset.of("+09:00");

    public List<OhlcvResponse> fetchHistoricalOhlcv(String ticker, String interval, int days, String endDateParam, String appKey, String appSecret) {
        String accessToken = kisTokenManager.getAccessToken(appKey, appSecret);
        if (accessToken == null) {
            log.error("Failed to get access token for KIS API");
            return Collections.emptyList();
        }

        if ("1m".equals(interval)) {
            return fetchMinuteOhlcv(ticker, appKey, appSecret, accessToken, endDateParam);
        }

        String url = apiUrl + "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";

        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", "Bearer " + accessToken);
        headers.set("appkey", appKey);
        headers.set("appsecret", appSecret);
        headers.set("tr_id", "FHKST03010100");
        headers.set("content-type", "application/json; charset=utf-8");

        String periodDiv = "D"; // 일봉
        if ("1w".equals(interval)) periodDiv = "W"; // 주봉
        else if ("1M".equals(interval)) periodDiv = "M"; // 월봉

        String endDate = (endDateParam != null) ? endDateParam : LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        int fetchDays;
        if ("D".equals(periodDiv)) {
            fetchDays = days;
        } else if ("W".equals(periodDiv)) {
            fetchDays = days * 7;
        } else {
            fetchDays = days * 30;
        }
        String startDate = LocalDate.parse(endDate, DateTimeFormatter.ofPattern("yyyyMMdd"))
                .minusDays(fetchDays)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_INPUT_ISCD", ticker)
                .queryParam("FID_PERIOD_DIV_CODE", periodDiv)
                .queryParam("FID_ORG_ADJ_PRC", "0")
                .queryParam("FID_INPUT_DATE_1", startDate)
                .queryParam("FID_INPUT_DATE_2", endDate);

        try {
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    builder.toUriString(),
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

            if (response.getBody() != null && response.getBody().containsKey("output2")) {
                List<Map<String, Object>> output2 = (List<Map<String, Object>>) response.getBody().get("output2");
                List<OhlcvResponse> result = new ArrayList<>();

                DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");

                for (Map<String, Object> item : output2) {
                    Object dateObj = item.get("stck_bsop_date");
                    if (dateObj == null || dateObj.toString().isEmpty()) continue;

                    String dateStr = dateObj.toString();
                    long epochSecond;
                    try {
                        epochSecond = LocalDate.parse(dateStr, inputFormatter)
                                .atStartOfDay()
                                .toEpochSecond(java.time.ZoneOffset.UTC);
                    } catch (Exception e) {
                        log.warn("Failed to parse date: {}", dateStr);
                        continue;
                    }

                    result.add(OhlcvResponse.builder()
                            .time(epochSecond)
                            .open(Double.parseDouble((String) item.get("stck_oprc")))
                            .high(Double.parseDouble((String) item.get("stck_hgpr")))
                            .low(Double.parseDouble((String) item.get("stck_lwpr")))
                            .close(Double.parseDouble((String) item.get("stck_clpr")))
                            .volume(Long.parseLong((String) item.get("acml_vol")))
                            .build());
                }                Collections.reverse(result);
                return result;
            }
        } catch (Exception e) {
            log.error("Error fetching historical data from KIS: {}", e.getMessage());
        }

        return Collections.emptyList();
    }

    private List<OhlcvResponse> fetchMinuteOhlcv(String ticker, String appKey, String appSecret, String accessToken, String endDateTime) {
        String url = apiUrl + "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice";

        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", "Bearer " + accessToken);
        headers.set("appkey", appKey);
        headers.set("appsecret", appSecret);
        headers.set("tr_id", "FHKST03010200");
        headers.set("content-type", "application/json; charset=utf-8");

        // endDateTime이 "yyyyMMddHHmmss" 형식이면 사용, 아니면 현재 시간
        String date, time;
        if (endDateTime != null && endDateTime.length() >= 14) {
            date = endDateTime.substring(0, 8);
            time = endDateTime.substring(8, 14);
        } else {
            java.time.ZonedDateTime nowKst = java.time.ZonedDateTime.now(KST_OFFSET);
            date = nowKst.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            time = nowKst.format(DateTimeFormatter.ofPattern("HHmmss"));
        }

        // KIS 분봉 API는 장 중(09:00~15:30 KST) 데이터만 반환함
        // 장 마감 후(15:30 이후)이면 15:30으로, 장 전(09:00 이전)이면 데이터 없음(empty 반환)
        int hour = Integer.parseInt(time.substring(0, 2));
        int minute = Integer.parseInt(time.substring(2, 4));
        if (hour > 15 || (hour == 15 && minute >= 30)) {
            time = "153000";
        } else if (hour < 9) {
            log.info("[KisApiService] Requested time {} is before market open — no minute data available", time);
            return Collections.emptyList();
        }
        log.info("[KisApiService] fetchMinuteOhlcv adjusted: date={}, time={}", date, time);

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_INPUT_ISCD", ticker)
                .queryParam("FID_ETC_CLS_CODE", "")
                .queryParam("FID_INPUT_DATE_1", date)
                .queryParam("FID_INPUT_HOUR_1", time)
                .queryParam("FID_PW_DATA_INCU_YN", "Y");

        try {
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    builder.toUriString(),
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

            if (response.getBody() != null) {
                String rtCd  = String.valueOf(response.getBody().get("rt_cd"));
                String msg1  = String.valueOf(response.getBody().get("msg1"));
                Object out2  = response.getBody().get("output2");
                log.info("[KisApiService] fetchMinuteOhlcv response: rt_cd={}, msg1={}, output2_type={}, output2={}",
                        rtCd, msg1, out2 != null ? out2.getClass().getSimpleName() : "null", out2);

                if (out2 instanceof List) {
                    List<Map<String, Object>> output2 = (List<Map<String, Object>>) out2;
                    List<OhlcvResponse> result = new ArrayList<>();
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

                    for (Map<String, Object> item : output2) {
                        String dStr = (String) item.get("stck_bsop_date");
                        String tStr = (String) item.get("stck_cntg_hour");
                        if (dStr == null || tStr == null || dStr.isEmpty() || tStr.isEmpty()) continue;

                        long epochSecond = java.time.LocalDateTime.parse(dStr + tStr, formatter)
                                .toEpochSecond(KST_OFFSET);

                        // 분봉 응답은 stck_clpr(종가) 대신 stck_prpr(현재가)를 close로 사용
                        String closeStr = (String) item.get("stck_clpr");
                        if (closeStr == null || closeStr.isEmpty()) closeStr = (String) item.get("stck_prpr");
                        if (closeStr == null || closeStr.isEmpty()) continue;

                        result.add(OhlcvResponse.builder()
                                .time(epochSecond)
                                .open(Double.parseDouble((String) item.get("stck_oprc")))
                                .high(Double.parseDouble((String) item.get("stck_hgpr")))
                                .low(Double.parseDouble((String) item.get("stck_lwpr")))
                                .close(Double.parseDouble(closeStr))
                                .volume(Long.parseLong((String) item.get("cntg_vol")))
                                .build());
                    }
                    Collections.reverse(result);
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("Error fetching minute data from KIS: {}", e.getMessage());
        }

        return Collections.emptyList();
    }

    public List<Map<String, String>> fetchAllListedStocks() {
        List<Map<String, String>> allStocks = new ArrayList<>();
        allStocks.addAll(fetchStocksFromKrx("stockMkt", "KOSPI"));
        allStocks.addAll(fetchStocksFromKrx("kosdaqMkt", "KOSDAQ"));
        log.info("[KisApiService] Total stocks fetched from KRX KIND: {}", allStocks.size());
        return allStocks;
    }

    private List<Map<String, String>> fetchStocksFromKrx(String marketType, String marketName) {
        String url = "https://kind.krx.co.kr/corpgeneral/corpList.do?method=download&searchType=13&marketType=" + marketType;
        try {
            org.jsoup.Connection.Response response = org.jsoup.Jsoup.connect(url)
                    .timeout(30000)
                    .userAgent("Mozilla/5.0 (compatible; StockApp/1.0)")
                    .ignoreContentType(true)
                    .execute();
            response.charset("EUC-KR");
            org.jsoup.nodes.Document doc = response.parse();

            List<Map<String, String>> result = new ArrayList<>();
            org.jsoup.select.Elements rows = doc.select("table tr");
            for (org.jsoup.nodes.Element row : rows) {
                org.jsoup.select.Elements cols = row.select("td");
                if (cols.size() < 3) continue;
                String name = cols.get(0).text().trim();
                String ticker = cols.get(2).text().replaceAll("[^0-9]", "").trim();
                if (ticker.length() == 6 && !name.isEmpty()) {
                    Map<String, String> stock = new java.util.HashMap<>();
                    stock.put("ticker", ticker);
                    stock.put("name", name);
                    stock.put("market", marketName);
                    result.add(stock);
                }
            }
            log.info("[KisApiService] Fetched {} stocks for {} from KRX KIND", result.size(), marketName);
            return result;
        } catch (Exception e) {
            log.error("[KisApiService] Error fetching stocks for market {} from KRX KIND: {}", marketName, e.getMessage());
        }
        return Collections.emptyList();
    }

    public List<Map<String, String>> searchStockByName(String query, String appKey, String appSecret) {
        String accessToken = kisTokenManager.getAccessToken(appKey, appSecret);
        if (accessToken == null) return Collections.emptyList();

        String url = apiUrl + "/uapi/domestic-stock/v1/quotations/search-stock-info";

        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", "Bearer " + accessToken);
        headers.set("appkey", appKey);
        headers.set("appsecret", appSecret);
        headers.set("tr_id", "HKSB3901R");
        headers.set("content-type", "application/json; charset=utf-8");

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("PRC_GUBUN", "0")
                .queryParam("PDNO", query);

        try {
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(builder.toUriString(), HttpMethod.GET, entity, Map.class);
            if (response.getBody() != null && response.getBody().containsKey("output")) {
                return (List<Map<String, String>>) response.getBody().get("output");
            }
        } catch (Exception e) {
            log.error("Error searching stock from KIS: {}", e.getMessage());
        }
        return Collections.emptyList();
    }
}
