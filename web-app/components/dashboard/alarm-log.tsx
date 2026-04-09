"use client"

import { useState, useEffect, useRef } from "react"
import { AlertTriangle, TrendingUp, Activity, Zap } from "lucide-react"
import { cn } from "@/lib/utils"

interface AlarmEntry {
  id: string
  timestamp: Date
  stockName: string
  stockSymbol: string
  type: "volume" | "price" | "pattern"
  message: string
  score: number
  severity: "high" | "medium" | "low"
}

const severityConfig = {
  high: {
    bg: "bg-chart-2/10",
    border: "border-chart-2/30",
    icon: "text-chart-2",
  },
  medium: {
    bg: "bg-chart-4/10",
    border: "border-chart-4/30",
    icon: "text-chart-4",
  },
  low: {
    bg: "bg-chart-3/10",
    border: "border-chart-3/30",
    icon: "text-chart-3",
  },
}

const stockNames = [
  { name: "삼성전자", symbol: "005930" },
  { name: "SK하이닉스", symbol: "000660" },
  { name: "NAVER", symbol: "035420" },
  { name: "현대차", symbol: "005380" },
  { name: "카카오", symbol: "035720" },
  { name: "LG화학", symbol: "051910" },
  { name: "셀트리온", symbol: "068270" },
]

const generateAlarm = (): AlarmEntry => {
  const stock = stockNames[Math.floor(Math.random() * stockNames.length)]
  const types: AlarmEntry["type"][] = ["volume", "price", "pattern"]
  const type = types[Math.floor(Math.random() * types.length)]
  const score = Math.random() * 3 + 1.5
  const severity: AlarmEntry["severity"] =
    score > 3.5 ? "high" : score > 2.5 ? "medium" : "low"

  const messages = {
    volume: `비정상적 거래량 급증 (Z-Score: ${score.toFixed(2)})`,
    price: `급격한 가격 변동 감지 (편차: ${(score * 0.8).toFixed(2)}%)`,
    pattern: `이상 거래 패턴 탐지 (RRCF Score: ${score.toFixed(2)})`,
  }

  return {
    id: Math.random().toString(36).substr(2, 9),
    timestamp: new Date(),
    stockName: stock.name,
    stockSymbol: stock.symbol,
    type,
    message: messages[type],
    score,
    severity,
  }
}

export function AlarmLog() {
  const [alarms, setAlarms] = useState<AlarmEntry[]>([])
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    // Initial alarms
    setAlarms(
      Array.from({ length: 5 }, () => {
        const alarm = generateAlarm()
        alarm.timestamp = new Date(
          Date.now() - Math.random() * 1000 * 60 * 5
        )
        return alarm
      }).sort((a, b) => b.timestamp.getTime() - a.timestamp.getTime())
    )

    // Generate new alarms periodically
    const interval = setInterval(() => {
      if (Math.random() > 0.6) {
        setAlarms((prev) => [generateAlarm(), ...prev.slice(0, 19)])
      }
    }, 3000)

    return () => clearInterval(interval)
  }, [])

  const getIcon = (type: AlarmEntry["type"]) => {
    switch (type) {
      case "volume":
        return Activity
      case "price":
        return TrendingUp
      case "pattern":
        return Zap
    }
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between border-b border-border px-4 py-2">
        <div className="flex items-center gap-2">
          <AlertTriangle className="h-4 w-4 text-accent" />
          <h3 className="text-sm font-semibold text-foreground">
            실시간 알람 로그
          </h3>
        </div>
        <span className="text-xs text-muted-foreground">
          {alarms.length}건의 알람
        </span>
      </div>
      <div
        ref={scrollRef}
        className="flex-1 overflow-y-auto font-mono text-xs"
      >
        {alarms.map((alarm, index) => {
          const Icon = getIcon(alarm.type)
          const config = severityConfig[alarm.severity]

          return (
            <div
              key={alarm.id}
              className={cn(
                "flex items-start gap-3 border-b border-border/30 px-4 py-2.5 transition-colors hover:bg-secondary/30",
                index === 0 && "animate-in fade-in slide-in-from-top-2 duration-300"
              )}
            >
              <div
                className={cn(
                  "mt-0.5 rounded p-1",
                  config.bg,
                  "border",
                  config.border
                )}
              >
                <Icon className={cn("h-3 w-3", config.icon)} />
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-muted-foreground">
                    {alarm.timestamp.toLocaleTimeString("ko-KR", {
                      hour: "2-digit",
                      minute: "2-digit",
                      second: "2-digit",
                    })}
                  </span>
                  <span className="font-semibold text-foreground">
                    [{alarm.stockName}]
                  </span>
                </div>
                <p className="mt-0.5 text-foreground/80">{alarm.message}</p>
              </div>
              <span
                className={cn(
                  "flex-shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium uppercase",
                  alarm.severity === "high" && "bg-chart-2/20 text-chart-2",
                  alarm.severity === "medium" && "bg-chart-4/20 text-chart-4",
                  alarm.severity === "low" && "bg-chart-3/20 text-chart-3"
                )}
              >
                {alarm.severity}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}
