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
import java.util.Map;
import java.util.stream.Collectors;

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

    public void saveOhlcv(String ticker, OhlcvResponse ohlcv) {
        try {
            WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();
            Point point = Point.measurement("stock_prices")
                    .addTag("ticker", ticker)
                    .addField("open", ohlcv.getOpen())
                    .addField("high", ohlcv.getHigh())
                    .addField("low", ohlcv.getLow())
                    .addField("close", ohlcv.getClose())
                    .addField("price", ohlcv.getClose()) // 실시간 틱과 호환성 위해 close를 price로도 저장
                    .addField("volume", ohlcv.getVolume())
                    .time(Instant.ofEpochSecond(ohlcv.getTime()), WritePrecision.S);
            writeApi.writePoint(bucket, influxOrg, point);
        } catch (Exception e) {
            log.error("Failed to save historical OHLCV to InfluxDB: {}", e.getMessage());
        }
    }

    public void saveOhlcvList(String ticker, List<OhlcvResponse> list) {
        list.forEach(item -> saveOhlcv(ticker, item));
    }

    private List<OhlcvResponse> executeOhlcvQuery(String query, String ticker) {
        List<OhlcvResponse> result = new ArrayList<>();
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(query, influxOrg);
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    // 필드가 이미 존재하면(캐시된 데이터) 해당 값을 사용, 없으면 집계된 값을 사용
                    Double open = getDoubleValue(record, "open", "price");
                    Double high = getDoubleValue(record, "high", "price");
                    Double low = getDoubleValue(record, "low", "price");
                    Double close = getDoubleValue(record, "close", "price");
                    Long volume = record.getValueByKey("volume") != null ? ((Number) record.getValueByKey("volume")).longValue() : 0L;

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

    private String buildOhlcvQuery(String ticker, String interval, String start, String stop) {
        // 이미 OHLC 필드가 있는 경우(캐시)와 없는 경우(실시간 틱)를 구분하여 처리
        // 'open' 필드가 있는 포인트는 이미 집계된 과거 데이터임
        return String.format(
                "data = from(bucket: \"%s\") " +
                "|> range(start: %s, stop: %s) " +
                "|> filter(fn: (r) => r[\"_measurement\"] == \"stock_prices\") " +
                "|> filter(fn: (r) => r[\"ticker\"] == \"%s\")\n" +
                "\n" +
                "// 1. 이미 OHLC로 저장된 데이터 (캐시)\n" +
                "cached = data |> filter(fn: (r) => r[\"_field\"] == \"open\" or r[\"_field\"] == \"high\" or r[\"_field\"] == \"low\" or r[\"_field\"] == \"close\" or r[\"_field\"] == \"volume\")\n" +
                "\n" +
                "// 2. 실시간 틱 데이터 (price 필드만 있고 open은 없는 데이터만 선택)\n" +
                "ticks = data \n" +
                "  |> pivot(rowKey:[\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")\n" +
                "  |> filter(fn: (r) => exists r.price and not exists r.open)\n" +
                "\n" +
                "// 틱 데이터를 OHLC로 집계\n" +
                "agg_open = ticks |> aggregateWindow(every: %s, fn: (column, tables=<-) => tables |> first(), column: \"price\", createEmpty: false) |> set(key: \"_field\", value: \"open\")\n" +
                "agg_high = ticks |> aggregateWindow(every: %s, fn: (column, tables=<-) => tables |> max(), column: \"price\", createEmpty: false) |> set(key: \"_field\", value: \"high\")\n" +
                "agg_low = ticks |> aggregateWindow(every: %s, fn: (column, tables=<-) => tables |> min(), column: \"price\", createEmpty: false) |> set(key: \"_field\", value: \"low\")\n" +
                "agg_close = ticks |> aggregateWindow(every: %s, fn: (column, tables=<-) => tables |> last(), column: \"price\", createEmpty: false) |> set(key: \"_field\", value: \"close\")\n" +
                "agg_vol = ticks |> aggregateWindow(every: %s, fn: (column, tables=<-) => tables |> sum(), column: \"volume\", createEmpty: false) |> set(key: \"_field\", value: \"volume\")\n" +
                "\n" +
                "union(tables: [cached, agg_open, agg_high, agg_low, agg_close, agg_vol]) " +
                "|> pivot(rowKey:[\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\") " +
                "|> sort(columns: [\"_time\"])",
                bucket, start, stop, ticker, interval, interval, interval, interval, interval
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
