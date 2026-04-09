"use client"

import { useState, useEffect, useMemo } from "react"
import {
  Area,
  AreaChart,
  ResponsiveContainer,
  XAxis,
  YAxis,
  Tooltip,
  ReferenceDot,
} from "recharts"
import { AlertTriangle, TrendingUp, TrendingDown, Activity } from "lucide-react"
import { cn } from "@/lib/utils"
import type { Stock } from "./stock-list"

interface PriceDataPoint {
  time: string
  price: number
  volume: number
  isAnomaly?: boolean
  anomalyScore?: number
}

interface PriceChartProps {
  stock: Stock | null
}

// Generate mock price data with anomalies
const generatePriceData = (stock: Stock | null): PriceDataPoint[] => {
  if (!stock) return []

  const data: PriceDataPoint[] = []
  const basePrice = stock.price
  let currentPrice = basePrice * 0.98

  for (let i = 0; i < 60; i++) {
    const hour = Math.floor(9 + i / 10)
    const minute = (i % 10) * 6
    const time = `${hour.toString().padStart(2, "0")}:${minute.toString().padStart(2, "0")}`

    // Random walk with occasional spikes
    const volatility = Math.random() > 0.9 ? 0.02 : 0.005
    const change = (Math.random() - 0.5) * basePrice * volatility
    currentPrice = Math.max(currentPrice + change, basePrice * 0.9)

    const isAnomaly = Math.random() > 0.92
    const volume = Math.floor(
      Math.random() * 500000 * (isAnomaly ? 5 : 1) + 100000
    )

    data.push({
      time,
      price: currentPrice,
      volume,
      isAnomaly,
      anomalyScore: isAnomaly ? Math.random() * 2 + 2.5 : undefined,
    })
  }

  return data
}

export function PriceChart({ stock }: PriceChartProps) {
  const [data, setData] = useState<PriceDataPoint[]>([])
  const [isUpdating, setIsUpdating] = useState(false)

  useEffect(() => {
    setData(generatePriceData(stock))

    if (stock) {
      const interval = setInterval(() => {
        setIsUpdating(true)
        setTimeout(() => setIsUpdating(false), 300)

        setData((prev) => {
          if (prev.length === 0) return prev
          const lastPrice = prev[prev.length - 1].price
          const change = (Math.random() - 0.5) * lastPrice * 0.005
          const newPrice = lastPrice + change
          const isAnomaly = Math.random() > 0.95

          const newPoint: PriceDataPoint = {
            time: new Date().toLocaleTimeString("ko-KR", {
              hour: "2-digit",
              minute: "2-digit",
            }),
            price: newPrice,
            volume: Math.floor(Math.random() * 500000 + 100000),
            isAnomaly,
            anomalyScore: isAnomaly ? Math.random() * 2 + 2.5 : undefined,
          }

          return [...prev.slice(1), newPoint]
        })
      }, 2000)

      return () => clearInterval(interval)
    }
  }, [stock])

  const anomalyPoints = useMemo(
    () => data.filter((d) => d.isAnomaly),
    [data]
  )

  const priceRange = useMemo(() => {
    if (data.length === 0) return { min: 0, max: 100 }
    const prices = data.map((d) => d.price)
    const min = Math.min(...prices)
    const max = Math.max(...prices)
    const padding = (max - min) * 0.1
    return { min: min - padding, max: max + padding }
  }, [data])

  const priceChange = useMemo(() => {
    if (data.length < 2) return { value: 0, percent: 0 }
    const first = data[0].price
    const last = data[data.length - 1].price
    return {
      value: last - first,
      percent: ((last - first) / first) * 100,
    }
  }, [data])

  if (!stock) {
    return (
      <div className="flex h-full flex-col items-center justify-center text-muted-foreground">
        <Activity className="mb-3 h-12 w-12 opacity-30" />
        <p className="text-sm">종목을 선택하세요</p>
      </div>
    )
  }

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="flex items-start justify-between border-b border-border px-6 py-4">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-xl font-bold text-foreground">{stock.name}</h1>
            <span className="rounded bg-secondary px-2 py-0.5 text-xs text-muted-foreground">
              {stock.symbol}
            </span>
            {isUpdating && (
              <span className="flex items-center gap-1 text-xs text-chart-1">
                <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-chart-1" />
                LIVE
              </span>
            )}
          </div>
          <div className="mt-2 flex items-baseline gap-3">
            <span className="text-3xl font-bold tabular-nums text-foreground">
              {data.length > 0
                ? data[data.length - 1].price.toLocaleString("ko-KR", {
                    maximumFractionDigits: 0,
                  })
                : "-"}
            </span>
            <span className="text-lg text-muted-foreground">KRW</span>
          </div>
        </div>
        <div
          className={cn(
            "rounded-lg px-4 py-2 text-right",
            priceChange.value >= 0 ? "bg-chart-1/10" : "bg-chart-2/10"
          )}
        >
          <div
            className={cn(
              "flex items-center justify-end gap-1 text-lg font-semibold tabular-nums",
              priceChange.value >= 0 ? "text-chart-1" : "text-chart-2"
            )}
          >
            {priceChange.value >= 0 ? (
              <TrendingUp className="h-5 w-5" />
            ) : (
              <TrendingDown className="h-5 w-5" />
            )}
            {priceChange.value >= 0 ? "+" : ""}
            {priceChange.percent.toFixed(2)}%
          </div>
          <div
            className={cn(
              "text-sm tabular-nums",
              priceChange.value >= 0
                ? "text-chart-1/70"
                : "text-chart-2/70"
            )}
          >
            {priceChange.value >= 0 ? "+" : ""}
            {priceChange.value.toLocaleString("ko-KR", {
              maximumFractionDigits: 0,
            })}
          </div>
        </div>
      </div>

      {/* Chart */}
      <div className="flex-1 p-4">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
            <defs>
              <linearGradient id="priceGradient" x1="0" y1="0" x2="0" y2="1">
                <stop
                  offset="5%"
                  stopColor={priceChange.value >= 0 ? "oklch(0.65 0.2 145)" : "oklch(0.55 0.22 25)"}
                  stopOpacity={0.3}
                />
                <stop
                  offset="95%"
                  stopColor={priceChange.value >= 0 ? "oklch(0.65 0.2 145)" : "oklch(0.55 0.22 25)"}
                  stopOpacity={0}
                />
              </linearGradient>
            </defs>
            <XAxis
              dataKey="time"
              axisLine={false}
              tickLine={false}
              tick={{ fill: "oklch(0.6 0 0)", fontSize: 11 }}
              interval="preserveStartEnd"
            />
            <YAxis
              domain={[priceRange.min, priceRange.max]}
              axisLine={false}
              tickLine={false}
              tick={{ fill: "oklch(0.6 0 0)", fontSize: 11 }}
              tickFormatter={(value) =>
                value.toLocaleString("ko-KR", { maximumFractionDigits: 0 })
              }
              width={70}
            />
            <Tooltip
              contentStyle={{
                backgroundColor: "oklch(0.15 0.005 260)",
                border: "1px solid oklch(0.25 0.01 260)",
                borderRadius: "8px",
                boxShadow: "0 4px 20px rgba(0,0,0,0.3)",
              }}
              labelStyle={{ color: "oklch(0.7 0 0)", marginBottom: 4 }}
              itemStyle={{ color: "oklch(0.95 0 0)" }}
              formatter={(value: number) => [
                value.toLocaleString("ko-KR", { maximumFractionDigits: 0 }) +
                  " KRW",
                "가격",
              ]}
            />
            <Area
              type="monotone"
              dataKey="price"
              stroke={priceChange.value >= 0 ? "oklch(0.65 0.2 145)" : "oklch(0.55 0.22 25)"}
              strokeWidth={2}
              fill="url(#priceGradient)"
            />
            {/* Anomaly markers */}
            {anomalyPoints.map((point, index) => (
              <ReferenceDot
                key={index}
                x={point.time}
                y={point.price}
                r={6}
                fill="oklch(0.55 0.2 30)"
                stroke="oklch(0.75 0.18 60)"
                strokeWidth={2}
              />
            ))}
          </AreaChart>
        </ResponsiveContainer>
      </div>

      {/* Anomaly Legend */}
      {anomalyPoints.length > 0 && (
        <div className="flex items-center gap-2 border-t border-border px-6 py-3">
          <AlertTriangle className="h-4 w-4 text-accent" />
          <span className="text-xs text-muted-foreground">
            이상 징후 감지: {anomalyPoints.length}건
          </span>
          <div className="ml-auto flex items-center gap-1.5">
            <span className="h-3 w-3 rounded-full bg-accent" />
            <span className="text-xs text-muted-foreground">이상 탐지 마커</span>
          </div>
        </div>
      )}
    </div>
  )
}
