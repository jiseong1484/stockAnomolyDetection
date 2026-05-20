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

        int fetchDays = ("D".equals(periodDiv)) ? days : days * 7;
        String startDate = LocalDate.parse(endDate, DateTimeFormatter.ofPattern("yyyyMMdd"))
                .minusDays(fetchDays)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_INPUT_ISCD", ticker)
                .queryParam("FID_PERIOD_DIV_CODE", periodDiv)
                .queryParam("FID_ORG_ADJ_PRC", "1")
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

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                .queryParam("FID_INPUT_ISCD", ticker)
                .queryParam("FID_ETC_CLS_CODE", "")
                .queryParam("FID_INPUT_HOUR_1", time)
                .queryParam("FID_PW_DATA_INXC_YN", "Y"); // 연속 조회 활성화

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

                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

                for (Map<String, Object> item : output2) {
                    String dStr = (String) item.get("stck_bsop_date");
                    String tStr = (String) item.get("stck_cntg_hour");
                    if (dStr == null || tStr == null) continue;

                    long epochSecond = java.time.LocalDateTime.parse(dStr + tStr, formatter)
                            .toEpochSecond(KST_OFFSET);

                    result.add(OhlcvResponse.builder()
                            .time(epochSecond)
                            .open(Double.parseDouble((String) item.get("stck_oprc")))
                            .high(Double.parseDouble((String) item.get("stck_hgpr")))
                            .low(Double.parseDouble((String) item.get("stck_lwpr")))
                            .close(Double.parseDouble((String) item.get("stck_clpr")))
                            .volume(Long.parseLong((String) item.get("cntg_vol")))
                            .build());
                }
                Collections.reverse(result);
                return result;
            }
        } catch (Exception e) {
            log.error("Error fetching minute data from KIS: {}", e.getMessage());
        }

        return Collections.emptyList();
    }}
