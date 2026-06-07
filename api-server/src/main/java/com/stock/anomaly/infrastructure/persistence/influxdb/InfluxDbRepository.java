package com.stock.anomaly.infrastructure.persistence.influxdb;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.stock.anomaly.domain.stock.StockTick;
import com.stock.anomaly.web.user.dto.OhlcvResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class InfluxDbRepository {

    private final InfluxDBClient influxDBClient;

    @Value("${spring.influxdb.bucket}")
    private String bucket;

    @Value("${spring.influxdb.org}")
    private String influxOrg;

    public void save(StockTick tick) {
        try {
            WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();
            Point point = Point.measurement("stock_prices")
                    .addTag("ticker", tick.getTicker())
                    .addField("price", Double.parseDouble(tick.getPrice()))
                    .addField("volume", Long.parseLong(tick.getVolume()))
                    .time(Instant.ofEpochMilli(tick.getTimestamp()), WritePrecision.MS);
            writeApi.writePoint(bucket, influxOrg, point);
        } catch (Exception e) {
            log.error("Failed to save stock tick to InfluxDB: {}", e.getMessage());
        }
    }

    // interval 태그를 추가해 일봉/주봉/월봉 데이터를 시리즈 단위로 구분
    public void saveOhlcv(String ticker, String interval, OhlcvResponse ohlcv) {
        try {
            WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();
            Point point = Point.measurement("stock_prices")
                    .addTag("ticker", ticker)
                    .addTag("interval", interval)
                    .addField("open", ohlcv.getOpen())
                    .addField("high", ohlcv.getHigh())
                    .addField("low", ohlcv.getLow())
                    .addField("close", ohlcv.getClose())
                    .addField("price", ohlcv.getClose())
                    .addField("volume", ohlcv.getVolume())
                    .time(Instant.ofEpochSecond(ohlcv.getTime()), WritePrecision.S);
            writeApi.writePoint(bucket, influxOrg, point);
        } catch (Exception e) {
            log.error("Failed to save historical OHLCV to InfluxDB: {}", e.getMessage());
        }
    }

    public void saveOhlcvList(String ticker, String interval, List<OhlcvResponse> list) {
        list.forEach(item -> saveOhlcv(ticker, interval, item));
    }

    private List<OhlcvResponse> executeOhlcvQuery(String query, String ticker) {
        List<OhlcvResponse> result = new ArrayList<>();
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(query, influxOrg);
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    Double open = getDoubleValue(record, "open", "price");
                    Double high = getDoubleValue(record, "high", "price");
                    Double low = getDoubleValue(record, "low", "price");
                    Double close = getDoubleValue(record, "close", "price");
                    Long volume = record.getValueByKey("volume") != null
                            ? ((Number) record.getValueByKey("volume")).longValue() : 0L;

                    result.add(OhlcvResponse.builder()
                            .time(record.getTime().getEpochSecond())
                            .open(open)
                            .high(high)
                            .low(low)
                            .close(close)
                            .volume(volume)
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to query OHLCV from InfluxDB for ticker {}: {}", ticker, e.getMessage());
        }
        return result;
    }

    private Double getDoubleValue(FluxRecord record, String field, String fallbackField) {
        Object val = record.getValueByKey(field);
        if (val == null) val = record.getValueByKey(fallbackField);
        return val != null ? ((Number) val).doubleValue() : 0.0;
    }

    public List<OhlcvResponse> findOhlcv(String ticker, String interval, Instant start, Instant end) {
        String query = buildOhlcvQuery(ticker, interval, start.toString(), end.toString());
        return executeOhlcvQuery(query, ticker);
    }

    public List<OhlcvResponse> findOhlcv(String ticker, String interval, int days) {
        String query = buildOhlcvQuery(ticker, interval, "-" + days + "d", "now()");
        return executeOhlcvQuery(query, ticker);
    }

    // InfluxDB Flux duration literals: 1m(분), 1h, 1d, 1w, 1mo(월)
    // 프론트에서 쓰는 "1M"은 Flux에서 유효하지 않으므로 "1mo"로 변환
    private String toFluxInterval(String interval) {
        if ("1M".equals(interval)) return "1mo";
        return interval;
    }

    private String buildOhlcvQuery(String ticker, String interval, String start, String stop) {
        String fluxInterval = toFluxInterval(interval);
        return String.format(
                "data = from(bucket: \"%s\")\n" +
                "  |> range(start: %s, stop: %s)\n" +
                "  |> filter(fn: (r) => r[\"_measurement\"] == \"stock_prices\")\n" +
                "  |> filter(fn: (r) => r[\"ticker\"] == \"%s\")\n\n" +

                // 캐시된 OHLCV: interval 태그 있음, _field/_value 포맷 유지
                "cached = data\n" +
                "  |> filter(fn: (r) => exists r[\"interval\"] and r[\"interval\"] == \"%s\")\n" +
                "  |> filter(fn: (r) => r[\"_field\"] == \"open\" or r[\"_field\"] == \"high\" or r[\"_field\"] == \"low\" or r[\"_field\"] == \"close\" or r[\"_field\"] == \"volume\")\n\n" +

                // 실시간 틱: interval 태그 없음. pivot 전에 분리해야 _value 컬럼이 존재
                "price_ticks = data\n" +
                "  |> filter(fn: (r) => not exists r[\"interval\"] and r[\"_field\"] == \"price\")\n\n" +
                "vol_ticks = data\n" +
                "  |> filter(fn: (r) => not exists r[\"interval\"] and r[\"_field\"] == \"volume\")\n\n" +

                // aggregateWindow는 _value 컬럼에 동작 — pivot 전이므로 정상 동작
                "agg_open  = price_ticks |> aggregateWindow(every: %s, fn: first, createEmpty: false) |> set(key: \"_field\", value: \"open\")\n" +
                "agg_high  = price_ticks |> aggregateWindow(every: %s, fn: max,   createEmpty: false) |> set(key: \"_field\", value: \"high\")\n" +
                "agg_low   = price_ticks |> aggregateWindow(every: %s, fn: min,   createEmpty: false) |> set(key: \"_field\", value: \"low\")\n" +
                "agg_close = price_ticks |> aggregateWindow(every: %s, fn: last,  createEmpty: false) |> set(key: \"_field\", value: \"close\")\n" +
                "agg_vol   = vol_ticks   |> aggregateWindow(every: %s, fn: sum,   createEmpty: false) |> set(key: \"_field\", value: \"volume\")\n\n" +

                "union(tables: [cached, agg_open, agg_high, agg_low, agg_close, agg_vol])\n" +
                "  |> pivot(rowKey:[\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")\n" +
                "  |> sort(columns: [\"_time\"])",
                bucket, start, stop, ticker,
                interval,
                fluxInterval, fluxInterval, fluxInterval, fluxInterval, fluxInterval
        );
    }

    public List<StockTick> findRecentTicks(String ticker, int minutes) {
        String query = String.format(
                "from(bucket: \"%s\") " +
                "|> range(start: -%dm) " +
                "|> filter(fn: (r) => r[\"_measurement\"] == \"stock_prices\") " +
                "|> filter(fn: (r) => r[\"ticker\"] == \"%s\") " +
                "|> pivot(rowKey:[\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")",
                bucket, minutes, ticker
        );

        List<StockTick> ticks = new ArrayList<>();
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(query, influxOrg);
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    ticks.add(StockTick.builder()
                            .ticker(ticker)
                            .price(String.valueOf(record.getValueByKey("price")))
                            .volume(String.valueOf(record.getValueByKey("volume")))
                            .timestamp(record.getTime().toEpochMilli())
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to query InfluxDB for ticker {}: {}", ticker, e.getMessage());
        }
        return ticks;
    }
}
