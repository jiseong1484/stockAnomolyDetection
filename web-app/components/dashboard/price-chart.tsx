"use client"

import { useState, useEffect, useRef, useMemo } from "react"
import { createChart, IChartApi, ISeriesApi, ColorType, CandlestickSeries, HistogramSeries } from "lightweight-charts"
import { TrendingUp, TrendingDown, Activity } from "lucide-react"
import { cn } from "@/lib/utils"
import { API_BASE_URL } from "@/lib/api"
import type { Stock } from "./stock-list"

interface OhlcvPoint {
  time: number
  open: number
  high: number
  low: number
  close: number
  volume: number
}

interface PriceChartProps {
  selectedStock: Stock | null
}

export function PriceChart({ selectedStock }: PriceChartProps) {
  const chartContainerRef = useRef<HTMLDivElement>(null)
  const chartApi = useRef<IChartApi | null>(null)
  const candleSeries = useRef<ISeriesApi<"Candlestick"> | null>(null)
  const volumeSeries = useRef<ISeriesApi<"Histogram"> | null>(null)

  const [isChartReady, setIsChartReady] = useState(false)
  const [isHistoryLoaded, setIsHistoryLoaded] = useState(false)
  const [isLoadingMore, setIsLoadingMore] = useState(false)
  const [ohlcvData, setOhlcvData] = useState<OhlcvPoint[]>([])
  const ohlcvDataRef = useRef<OhlcvPoint[]>([])
  
  // ohlcvData 상태를 ref에 동기화 (클로저 문제 해결)
  useEffect(() => {
    ohlcvDataRef.current = ohlcvData
  }, [ohlcvData])

  const [interval, setChartInterval] = useState<"1d" | "1m" | "1w" | "1M">("1d")
  const intervalRef = useRef(interval)
  useEffect(() => {
    intervalRef.current = interval
  }, [interval])
  const [legendData, setLegendData] = useState<{ open: number; high: number; low: number; close: number } | null>(null)
  
  const tickBuffer = useRef<{ price: number; timestamp: number }[]>([])
  const isFetchingRef = useRef(false)
  const noMoreDataRef = useRef(false)  // 더 이상 과거 데이터가 없을 때 무한 요청 방지
  const isHistoryLoadedRef = useRef(false)
  useEffect(() => {
    isHistoryLoadedRef.current = isHistoryLoaded
  }, [isHistoryLoaded])

  // KST(한국 표준시) 날짜 문자열 생성 헬퍼 (YYYY-MM-DD)
  const getKstDateString = (timestamp: number) => {
    const ms = timestamp < 10000000000 ? timestamp * 1000 : timestamp;
    const date = new Date(ms + (9 * 60 * 60 * 1000));
    return date.getUTCFullYear() + '-' + 
           String(date.getUTCMonth() + 1).padStart(2, '0') + '-' + 
           String(date.getUTCDate()).padStart(2, '0');
  };

  // 차트 데이터 업데이트 중앙화 로직 (중복 제거 및 정렬 포함)
  const updateChartData = (data: OhlcvPoint[]) => {
    if (!candleSeries.current || !volumeSeries.current) return;

    const dataMap = new Map<string | number, any>();
    const sortedData = [...data].sort((a, b) => a.time - b.time);

    sortedData.forEach(d => {
      const isDaily = (intervalRef.current === "1d" || intervalRef.current === "1w" || intervalRef.current === "1M");
      const timeKey = isDaily ? getKstDateString(d.time) : d.time;
      
      dataMap.set(timeKey, {
        time: timeKey as any,
        open: d.open,
        high: d.high,
        low: d.low,
        close: d.close,
        volume: d.volume
      });
    });

    const finalData = Array.from(dataMap.values());
    
    try {
      candleSeries.current.setData(finalData);
      volumeSeries.current.setData(finalData.map(d => ({
        time: d.time,
        value: d.volume,
        color: d.close >= d.open ? "rgba(38, 166, 154, 0.3)" : "rgba(239, 83, 80, 0.3)"
      })));
    } catch (err) {
      console.error("[PriceChart] Error in setData:", err);
    }
  };

  // 1. 차트 초기화
  useEffect(() => {
    if (!selectedStock || !chartContainerRef.current) return

    const chart = createChart(chartContainerRef.current, {
      layout: {
        background: { type: ColorType.Solid, color: "transparent" },
        textColor: "#a0a0a0",
      },
      grid: {
        vertLines: { color: "#2b2b2b" },
        horzLines: { color: "#2b2b2b" },
      },
      timeScale: {
        borderColor: "#2b2b2b",
        timeVisible: true,
        secondsVisible: false,
      },
      crosshair: {
        mode: 0,
        vertLine: { labelBackgroundColor: '#4c525e' },
        horzLine: { labelBackgroundColor: '#4c525e' },
      },
    })

    const candlestick = chart.addSeries(CandlestickSeries, {
      upColor: "#26a69a",
      downColor: "#ef5350",
      borderVisible: false,
      wickUpColor: "#26a69a",
      wickDownColor: "#ef5350",
    })

    const volume = chart.addSeries(HistogramSeries, {
      color: "#26a69a",
      priceFormat: { type: "volume" },
      priceScaleId: "",
    })
    
    volume.priceScale().applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    })

    chartApi.current = chart
    candleSeries.current = candlestick
    volumeSeries.current = volume
    setIsChartReady(true)

    const handleResize = () => {
      if (chartContainerRef.current) {
        chart.applyOptions({ width: chartContainerRef.current.clientWidth })
      }
    }
    window.addEventListener("resize", handleResize)

    chart.subscribeCrosshairMove((param) => {
      if (param.time && param.seriesData.has(candlestick)) {
        const data = param.seriesData.get(candlestick) as any;
        setLegendData({
          open: data.open,
          high: data.high,
          low: data.low,
          close: data.close,
        });
      } else {
        setLegendData(null);
      }
    });

    noMoreDataRef.current = false  // 종목/interval 전환 시 리셋

    // 무한 스크롤 및 축소 감지 (동적 임계값 적용)
    chart.timeScale().subscribeVisibleLogicalRangeChange((range) => {
      if (!range) return;
      if (isFetchingRef.current || noMoreDataRef.current) return;

      const currentData = ohlcvDataRef.current;
      const barsCount = currentData.length;
      if (barsCount === 0) return;

      const visibleBars = range.to - range.from;

      // 화면에 보이는 양의 50% 지점에 도달하면 로딩 (최소 50개)
      const threshold = Math.max(visibleBars * 0.5, 50);

      if (range.from < threshold) {
        console.log(`[PriceChart] Dynamic Trigger Met: from(${range.from.toFixed(1)}) < threshold(${threshold.toFixed(1)})`);
        loadMore();
      }
    });

    return () => {
      window.removeEventListener("resize", handleResize)
      // interval 전환 시 이전 interval 데이터가 loadMore에 오염되지 않도록 ref를 즉시 초기화
      ohlcvDataRef.current = []
      noMoreDataRef.current = false
      setIsChartReady(false)
      chart.remove()
      // chart.remove() 이후 disposed된 시리즈에 setData()가 호출되지 않도록 ref를 비운다
      chartApi.current = null
      candleSeries.current = null
      volumeSeries.current = null
    }
  }, [selectedStock?.ticker, interval])

  const loadMore = async () => {
    const currentData = ohlcvDataRef.current;
    // isHistoryLoaded가 false면 아직 초기 데이터 로드 중이므로 스킵 (interval 전환 race condition 방지)
    if (isFetchingRef.current || currentData.length === 0 || !isHistoryLoadedRef.current) {
      console.log(`[PriceChart] loadMore skipped: isFetching=${isFetchingRef.current}, dataLength=${currentData.length}, historyLoaded=${isHistoryLoadedRef.current}`);
      return;
    }
    
    // 현재 가장 오래된 데이터의 시간 기준 하루 전날 계산
    // 배열의 실제 정렬 순서를 신뢰하지 않고 time 최솟값을 직접 찾는다
    // (백엔드 응답 순서가 항상 오름차순이라는 보장이 없어 currentData[0]이
    //  최신 데이터일 수 있음 — 이 경우 loadMore가 엉뚱한 날짜를 요청하게 됨)
    const earliestPoint = currentData.reduce(
      (min, d) => (d.time < min.time ? d : min),
      currentData[0]
    );
    const ms = earliestPoint.time < 10000000000 ? earliestPoint.time * 1000 : earliestPoint.time;
    const date = new Date(ms + (9 * 60 * 60 * 1000)); // KST 변환
    
    let endDateStr = "";
    if (interval === "1m") {
      // 분봉: 가장 오래된 데이터의 1분 전 KST 시간으로 endDate 계산
      // epoch second 에서 직접 60초를 빼서 계산 (Date 객체 조작 실수 방지)
      const prevMinuteEpochSec = earliestPoint.time - 60;
      const prevMs = prevMinuteEpochSec * 1000;
      const kst = new Date(prevMs + (9 * 60 * 60 * 1000)); // UTC+9 오프셋 더해 KST 시간 추출
      endDateStr = kst.getUTCFullYear() +
                  String(kst.getUTCMonth() + 1).padStart(2, '0') +
                  String(kst.getUTCDate()).padStart(2, '0') +
                  String(kst.getUTCHours()).padStart(2, '0') +
                  String(kst.getUTCMinutes()).padStart(2, '0') +
                  String(kst.getUTCSeconds()).padStart(2, '0');
    } else {
      date.setUTCDate(date.getUTCDate() - 1);
      endDateStr = date.getUTCFullYear() + 
                  String(date.getUTCMonth() + 1).padStart(2, '0') + 
                  String(date.getUTCDate()).padStart(2, '0');
    }
    
    console.log(`[PriceChart] Fetching more data before: ${endDateStr} (interval: ${interval})`);
    isFetchingRef.current = true;
    setIsLoadingMore(true);

    try {
      const token = localStorage.getItem("accessToken");
      const url = `${API_BASE_URL}/api/v1/stocks/${selectedStock?.ticker}/ohlcv?interval=${interval}&days=200&endDate=${endDateStr}`;
      
      const response = await fetch(url, {
        headers: { "Authorization": token || "" }
      });
      
      if (response.ok) {
        const moreData: OhlcvPoint[] = await response.json();
        console.log(`[PriceChart] Received ${moreData.length} more data points`);
        
        if (moreData.length > 0) {
          const combined = [...moreData, ...currentData];
          const uniqueSorted = Array.from(
            new Map(combined.map(item => [item.time, item])).values()
          ).sort((a, b) => a.time - b.time);

          // 실제로 새 데이터가 추가됐는지 확인 (중복 제거 후 개수 비교)
          if (uniqueSorted.length > currentData.length) {
            console.log(`[PriceChart] Prepending data. New total bars: ${uniqueSorted.length}`);
            setOhlcvData(uniqueSorted);
            updateChartData(uniqueSorted);
          } else {
            console.log("[PriceChart] Received data was all duplicates. Marking no more data.");
            noMoreDataRef.current = true;
          }
        } else {
          console.log("[PriceChart] No more data available from server. Stopping loadMore.");
          noMoreDataRef.current = true;
        }
      } else {
        console.error(`[PriceChart] Fetch failed with status: ${response.status}`);
      }
    } catch (e) {
      console.error("[PriceChart] loadMore error:", e);
    } finally {
      isFetchingRef.current = false;
      setIsLoadingMore(false);
    }
  };

  // 2. 히스토리 데이터 로드
  useEffect(() => {
    if (!selectedStock || !isChartReady) {
      if (!selectedStock) {
        setIsHistoryLoaded(false)
        setOhlcvData([])
        candleSeries.current?.setData([])
        volumeSeries.current?.setData([])
      }
      return
    }

    const fetchHistory = async () => {
      try {
        setIsHistoryLoaded(false)
        const token = localStorage.getItem("accessToken")
        const url = `${API_BASE_URL}/api/v1/stocks/${selectedStock.ticker}/ohlcv?interval=${interval}&days=500`;
        
        console.log(`[PriceChart] Initial fetch started. URL: ${url}`);
        
        const response = await fetch(url, {
          headers: { "Authorization": token || "" }
        })
        
        console.log(`[PriceChart] Initial fetch response status: ${response.status}`);
        
        if (!response.ok) {
          console.error(`[PriceChart] Initial fetch failed: ${response.statusText}`);
          return;
        }
        
        const history: OhlcvPoint[] = await response.json()
        console.log(`[PriceChart] Initial fetch success. History length: ${history.length}`);
        
        setOhlcvData(history);
        updateChartData(history);
        chartApi.current?.timeScale().fitContent();

        const lastHistoryTime = history.length > 0 ? history[history.length - 1].time : 0
        const pendingTicks = tickBuffer.current.filter(t => t.timestamp / 1000 > lastHistoryTime)
        if (pendingTicks.length > 0) {
          console.log(`[PriceChart] Applying ${pendingTicks.length} pending ticks`);
          pendingTicks.forEach(tick => applyTick(tick.price, tick.timestamp))
        }
        tickBuffer.current = []
      } catch (error) {
        console.error("[PriceChart] fetchHistory error:", error)
      } finally {
        setIsHistoryLoaded(true)
      }
    }

    fetchHistory()
  }, [selectedStock?.ticker, interval, isChartReady])

  // 3. 실시간 업데이트
  useEffect(() => {
    if (!selectedStock) return

    const price = parseFloat(selectedStock.price)
    if (isNaN(price) || price === 0) return

    const timestamp = Date.now()

    if (!isHistoryLoaded) {
      tickBuffer.current.push({ price, timestamp })
    } else {
      applyTick(price, timestamp)
    }
  }, [selectedStock?.price, isHistoryLoaded])

  const applyTick = (price: number, timestamp: number) => {
    if (!candleSeries.current || !ohlcvDataRef.current.length) return

    const isDaily = (intervalRef.current === "1d" || intervalRef.current === "1w" || intervalRef.current === "1M");
    const timeKey = isDaily ? getKstDateString(timestamp) : Math.floor(timestamp / 1000 / 60) * 60;

    setOhlcvData(prev => {
      const lastPoint = prev.length > 0 ? prev[prev.length - 1] : null;

      // lightweight-charts는 과거 시점의 데이터를 update할 수 없음
      // 마지막 데이터의 시간보다 작으면 업데이트 무시 (Cannot update oldest data 에러 방지)
      const lastTimeKey = lastPoint ? (isDaily ? getKstDateString(lastPoint.time) : Math.floor(lastPoint.time / 60) * 60) : null;
      
      // timeKey 비교 (문자열인 경우 사전순, 숫자인 경우 값 비교)
      if (lastTimeKey && timeKey < lastTimeKey) {
        return prev;
      }

      const isSameTime = lastTimeKey === timeKey;

      let updatedPoint: OhlcvPoint;
      let newHistory = [...prev];

      if (isSameTime && lastPoint) {
        updatedPoint = {
          ...lastPoint,
          high: Math.max(lastPoint.high, price),
          low:  Math.min(lastPoint.low,  price),
          close: price,
        };
        newHistory[newHistory.length - 1] = updatedPoint;
      } else {
        // 새 캔들: non-daily는 분 단위 정렬된 시간을 저장해 다음 비교와 일치시킴
        updatedPoint = {
          time: isDaily ? Math.floor(timestamp / 1000) : (timeKey as number),
          open:   price,
          high:   price,
          low:    price,
          close:  price,
          volume: 0,
        };
        newHistory.push(updatedPoint);
      }

      // 차트 update에는 반드시 차트에 이미 저장된 시간 키를 써야 함.
      // isSameTime 일 때 timeKey 대신 lastPoint.time(daily는 날짜 문자열)을 써서
      // "Cannot update oldest data" 오류를 방지한다.
      const chartTime = isSameTime && lastPoint
        ? (isDaily ? lastTimeKey : lastPoint.time)
        : timeKey;

      try {
        candleSeries.current?.update({
          time:  chartTime as any,
          open:  updatedPoint.open,
          high:  updatedPoint.high,
          low:   updatedPoint.low,
          close: updatedPoint.close,
        });
      } catch (e) {
        console.warn("[PriceChart] Update failed (chronological order):", e);
      }

      return newHistory;
    });
  }

  // KIS WebSocket/REST 가 제공하는 전일 대비 데이터를 직접 사용
  const priceChange = useMemo(() => {
    const percent = Number((selectedStock?.change ?? "0").replace("%", "").trim()) || 0
    const value   = Number(selectedStock?.changeAmount ?? "0") || 0
    return { value, percent }
  }, [selectedStock?.change, selectedStock?.changeAmount])

  // 차트가 붙어있는 DOM 노드(chartContainerRef)는 selectedStock 유무와 무관하게
  // 항상 마운트 상태를 유지해야 한다. 종목 선택이 해제될 때 이 노드가 통째로
  // 언마운트되면, lightweight-charts 내부 ResizeObserver 콜백이 비동기로 실행되며
  // 이미 사라진 DOM/캔버스 바인딩에 접근해 "Object is disposed" 에러가 발생한다.
  return (
    <div className="flex h-full flex-col relative">
      {selectedStock ? (
        <>
          <div className="flex items-start justify-between border-b border-border px-6 py-4">
            <div>
              <div className="flex items-center gap-3">
                <h1 className="text-xl font-bold text-foreground">{selectedStock.name}</h1>
                <span className="rounded bg-secondary px-2 py-0.5 text-xs text-muted-foreground">
                  {selectedStock.ticker}
                </span>
                <span className="flex items-center gap-1 text-xs text-chart-1">
                  <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-chart-1" />
                  LIVE
                </span>
              </div>
              <div className="mt-2 flex items-baseline gap-3">
                <span className="text-3xl font-bold tabular-nums text-foreground">
                  {parseFloat(selectedStock.price).toLocaleString("ko-KR")}
                </span>
                <span className="text-lg text-muted-foreground">KRW</span>
              </div>
            </div>
            <div className="flex flex-col items-end gap-3">
              <div className="flex items-center gap-1 rounded-lg bg-secondary/50 p-1">
                <button
                  onClick={() => setChartInterval("1m")}
                  className={cn(
                    "rounded px-3 py-1 text-xs font-medium transition-colors",
                    interval === "1m" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  1분
                </button>
                <button
                  onClick={() => setChartInterval("1d")}
                  className={cn(
                    "rounded px-3 py-1 text-xs font-medium transition-colors",
                    interval === "1d" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  1일
                </button>
                <button
                  onClick={() => setChartInterval("1w")}
                  className={cn(
                    "rounded px-3 py-1 text-xs font-medium transition-colors",
                    interval === "1w" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  1주
                </button>
                <button
                  onClick={() => setChartInterval("1M")}
                  className={cn(
                    "rounded px-3 py-1 text-xs font-medium transition-colors",
                    interval === "1M" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  1월
                </button>
              </div>
              <div className={cn("rounded-lg px-4 py-2 text-right", priceChange.value >= 0 ? "bg-chart-1/10" : "bg-chart-2/10")}>
                <div className={cn("flex items-center justify-end gap-1 text-lg font-semibold tabular-nums", priceChange.value >= 0 ? "text-chart-1" : "text-chart-2")}>
                  {priceChange.value >= 0 ? <TrendingUp className="h-5 w-5" /> : <TrendingDown className="h-5 w-5" />}
                  {priceChange.value >= 0 ? "+" : ""}{priceChange.percent.toFixed(2)}%
                </div>
                <div className={cn("text-sm tabular-nums", priceChange.value >= 0 ? "text-chart-1/70" : "text-chart-2/70")}>
                  {priceChange.value >= 0 ? "+" : ""}{priceChange.value.toLocaleString("ko-KR")}
                </div>
              </div>
            </div>
          </div>

          {/* OHLC Legend Overlay */}
          <div className="absolute left-6 top-32 z-10 flex gap-4 text-[11px] font-medium tabular-nums">
            {legendData ? (
              <>
                <div className="flex gap-1"><span className="text-muted-foreground">O:</span><span className={legendData.close >= legendData.open ? "text-chart-1" : "text-chart-2"}>{legendData.open.toLocaleString()}</span></div>
                <div className="flex gap-1"><span className="text-muted-foreground">H:</span><span className={legendData.close >= legendData.open ? "text-chart-1" : "text-chart-2"}>{legendData.high.toLocaleString()}</span></div>
                <div className="flex gap-1"><span className="text-muted-foreground">L:</span><span className={legendData.close >= legendData.open ? "text-chart-1" : "text-chart-2"}>{legendData.low.toLocaleString()}</span></div>
                <div className="flex gap-1"><span className="text-muted-foreground">C:</span><span className={legendData.close >= legendData.open ? "text-chart-1" : "text-chart-2"}>{legendData.close.toLocaleString()}</span></div>
              </>
            ) : ohlcvData.length > 0 && (
              <>
                <div className="flex gap-1"><span className="text-muted-foreground">O:</span><span>{ohlcvData[ohlcvData.length-1].open.toLocaleString()}</span></div>
                <div className="flex gap-1"><span className="text-muted-foreground">H:</span><span>{ohlcvData[ohlcvData.length-1].high.toLocaleString()}</span></div>
                <div className="flex gap-1"><span className="text-muted-foreground">L:</span><span>{ohlcvData[ohlcvData.length-1].low.toLocaleString()}</span></div>
                <div className="flex gap-1"><span className="text-muted-foreground">C:</span><span>{ohlcvData[ohlcvData.length-1].close.toLocaleString()}</span></div>
              </>
            )}
          </div>
        </>
      ) : (
        <div className="absolute inset-0 z-10 flex flex-col items-center justify-center bg-card text-muted-foreground">
          <Activity className="mb-3 h-12 w-12 opacity-30" />
          <p className="text-sm">종목을 선택하세요</p>
        </div>
      )}

      <div className="flex-1 p-4" ref={chartContainerRef} style={{ position: "relative" }} />

      {selectedStock && (
        <div className="flex items-center gap-2 border-t border-border px-6 py-3">
          <Activity className={cn("h-4 w-4", (isLoadingMore || !isHistoryLoaded) ? "animate-pulse text-muted-foreground" : "text-chart-1")} />
          <span className="text-xs text-muted-foreground">
            {isLoadingMore ? "과거 데이터 불러오는 중..." : (!isHistoryLoaded ? "데이터 로드 중..." : (ohlcvData.length === 0 ? "과거 데이터 부재 (실시간 대기 중)" : "실시간 스트리밍 중"))}
          </span>
        </div>
      )}
    </div>
  )
}
