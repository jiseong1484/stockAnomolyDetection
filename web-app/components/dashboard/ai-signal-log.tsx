"use client"

import { useEffect, useRef } from "react"
import { Sparkles, TrendingUp, TrendingDown, Minus } from "lucide-react"
import { cn } from "@/lib/utils"

export interface AiSignalEntry {
  id: string
  timestamp: Date
  ticker: string
  close: number
  bullProbability: number
  bearProbability: number
  direction: "BUY_READY" | "SELL_READY" | "NEUTRAL"
}

interface AiSignalLogProps {
  signals: AiSignalEntry[]
}

const directionConfig = {
  BUY_READY:  { label: "매수 신호", bg: "bg-chart-1/10", border: "border-chart-1/30", icon: "text-chart-1", badge: "bg-chart-1/20 text-chart-1" },
  SELL_READY: { label: "매도 신호", bg: "bg-chart-2/10", border: "border-chart-2/30", icon: "text-chart-2", badge: "bg-chart-2/20 text-chart-2" },
  NEUTRAL:    { label: "중립",     bg: "bg-muted/50",    border: "border-border",     icon: "text-muted-foreground", badge: "bg-muted text-muted-foreground" },
}

const getIcon = (direction: AiSignalEntry["direction"]) => {
  if (direction === "BUY_READY") return TrendingUp
  if (direction === "SELL_READY") return TrendingDown
  return Minus
}

export function AiSignalLog({ signals }: AiSignalLogProps) {
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = 0
    }
  }, [signals])

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between border-b border-border px-4 py-2">
        <div className="flex items-center gap-2">
          <Sparkles className="h-4 w-4 text-accent" />
          <h3 className="text-sm font-semibold text-foreground">AI 시그널</h3>
        </div>
        <span className="text-xs text-muted-foreground">{signals.length}건</span>
      </div>

      <div ref={scrollRef} className="flex-1 overflow-y-auto font-mono text-xs">
        {signals.length === 0 ? (
          <div className="flex h-full items-center justify-center text-muted-foreground text-xs">
            AI 시그널 대기 중...
          </div>
        ) : (
          signals.map((signal, index) => {
            const Icon = getIcon(signal.direction)
            const config = directionConfig[signal.direction]
            return (
              <div
                key={signal.id}
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
                      {signal.timestamp.toLocaleTimeString("ko-KR", {
                        hour: "2-digit", minute: "2-digit", second: "2-digit",
                      })}
                    </span>
                    <span className="font-semibold text-foreground">[{signal.ticker}]</span>
                    <span className="text-muted-foreground">
                      ₩{signal.close.toLocaleString()}
                    </span>
                  </div>
                  <p className="mt-0.5 text-foreground/80">
                    {config.label} — 상승 {(signal.bullProbability * 100).toFixed(1)}% / 하락 {(signal.bearProbability * 100).toFixed(1)}%
                  </p>
                </div>
                <span className={cn("flex-shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium", config.badge)}>
                  {(signal.bullProbability * 100).toFixed(0)}%
                </span>
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}
