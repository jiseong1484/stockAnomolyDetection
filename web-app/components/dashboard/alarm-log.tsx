"use client"

import { useEffect, useRef } from "react"
import { AlertTriangle, TrendingUp, Activity, Zap } from "lucide-react"
import { cn } from "@/lib/utils"

export interface AlarmEntry {
  id: string
  timestamp: Date
  ticker: string
  zscore: number
  price_change_pct: number
  current_price: number
  severity: "HIGH" | "MEDIUM" | "LOW"
}

interface AlarmLogProps {
  alarms: AlarmEntry[]
}

const severityConfig = {
  HIGH:   { bg: "bg-chart-2/10", border: "border-chart-2/30", icon: "text-chart-2",  badge: "bg-chart-2/20 text-chart-2" },
  MEDIUM: { bg: "bg-chart-4/10", border: "border-chart-4/30", icon: "text-chart-4",  badge: "bg-chart-4/20 text-chart-4" },
  LOW:    { bg: "bg-chart-3/10", border: "border-chart-3/30", icon: "text-chart-3",  badge: "bg-chart-3/20 text-chart-3" },
}

const getIcon = (severity: AlarmEntry["severity"]) => {
  if (severity === "HIGH")   return Zap
  if (severity === "MEDIUM") return TrendingUp
  return Activity
}

const getMessage = (alarm: AlarmEntry) => {
  if (alarm.severity === "HIGH")
    return `강한 이상 신호 — Z-Score: ${alarm.zscore.toFixed(2)}, 가격변동: ${alarm.price_change_pct > 0 ? "+" : ""}${alarm.price_change_pct.toFixed(2)}%`
  if (alarm.severity === "MEDIUM")
    return `거래량+가격 이상 — Z-Score: ${alarm.zscore.toFixed(2)}, 가격변동: ${alarm.price_change_pct > 0 ? "+" : ""}${alarm.price_change_pct.toFixed(2)}%`
  return `거래량 이상 감지 — Z-Score: ${alarm.zscore.toFixed(2)}`
}

export function AlarmLog({ alarms }: AlarmLogProps) {
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = 0
    }
  }, [alarms])

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between border-b border-border px-4 py-2">
        <div className="flex items-center gap-2">
          <AlertTriangle className="h-4 w-4 text-accent" />
          <h3 className="text-sm font-semibold text-foreground">실시간 알람 로그</h3>
        </div>
        <span className="text-xs text-muted-foreground">{alarms.length}건의 알람</span>
      </div>

      <div ref={scrollRef} className="flex-1 overflow-y-auto font-mono text-xs">
        {alarms.length === 0 ? (
          <div className="flex h-full items-center justify-center text-muted-foreground text-xs">
            이상 신호 대기 중...
          </div>
        ) : (
          alarms.map((alarm, index) => {
            const Icon = getIcon(alarm.severity)
            const config = severityConfig[alarm.severity]
            return (
              <div
                key={alarm.id}
                className={cn(
                  "flex items-start gap-3 border-b border-border/30 px-4 py-2.5 transition-colors hover:bg-secondary/30",
                  index === 0 && "animate-in fade-in slide-in-from-top-2 duration-300"
                )}
              >
                <div className={cn("mt-0.5 rounded p-1", config.bg, "border", config.border)}>
                  <Icon className={cn("h-3 w-3", config.icon)} />
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="text-muted-foreground">
                      {alarm.timestamp.toLocaleTimeString("ko-KR", {
                        hour: "2-digit", minute: "2-digit", second: "2-digit",
                      })}
                    </span>
                    <span className="font-semibold text-foreground">[{alarm.ticker}]</span>
                    <span className="text-muted-foreground">
                      ₩{alarm.current_price.toLocaleString()}
                    </span>
                  </div>
                  <p className="mt-0.5 text-foreground/80">{getMessage(alarm)}</p>
                </div>
                <span className={cn("flex-shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium", config.badge)}>
                  {alarm.severity}
                </span>
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}
