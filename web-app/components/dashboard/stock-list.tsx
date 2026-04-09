"use client"

import { cn } from "@/lib/utils"
import { TrendingUp, TrendingDown, AlertTriangle } from "lucide-react"

interface Stock {
  id: string
  ticker: string
  name: string
  price: string
  change: string
  changeAmount: string
  status: string
}

interface StockListProps {
  stocks: Stock[]
  selectedStock: Stock | null
  onSelectStock: (stock: Stock) => void
}

export function StockList({ stocks, selectedStock, onSelectStock }: StockListProps) {
  return (
    <div className="flex h-full flex-col">
      <div className="border-b border-border px-4 py-3">
        <h2 className="text-sm font-semibold tracking-wide text-foreground/90">
          관심 종목
        </h2>
        <p className="mt-0.5 text-xs text-muted-foreground">
          실시간 모니터링 중 ({stocks.length}개)
        </p>
      </div>
      <div className="flex-1 overflow-y-auto">
        {stocks.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center p-6 text-center">
            <p className="text-sm text-muted-foreground">관심 종목이 없습니다.</p>
            <p className="mt-1 text-xs text-muted-foreground/60">상단 검색창에서 종목을 추가해보세요.</p>
          </div>
        ) : (
          stocks.map((stock) => (
            <button
              key={stock.id}
              onClick={() => onSelectStock(stock)}
              className={cn(
                "group relative flex w-full items-center justify-between border-b border-border/50 px-4 py-4 text-left transition-all hover:bg-secondary/50",
                selectedStock?.id === stock.id && "bg-secondary/70"
              )}
            >
              {stock.status === "anomaly" && (
                <div className="absolute left-0 top-0 h-full w-0.5 bg-destructive" />
              )}
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="truncate text-sm font-medium text-foreground">
                    {stock.name}
                  </span>
                  {stock.status === "anomaly" && (
                    <AlertTriangle className="h-3.5 w-3.5 flex-shrink-0 text-destructive" />
                  )}
                </div>
                <span className="text-xs text-muted-foreground">
                  {stock.ticker}
                </span>
              </div>
              <div className="ml-3 text-right">
                <div className="text-sm font-medium tabular-nums text-foreground">
                  {Number(stock.price).toLocaleString("ko-KR")}
                </div>
                <div
                  className={cn(
                    "flex items-center justify-end gap-0.5 text-xs font-medium tabular-nums",
                    Number(stock.changeAmount) >= 0 ? "text-chart-1" : "text-chart-2"
                  )}
                >
                  {Number(stock.changeAmount) >= 0 ? (
                    <TrendingUp className="h-3 w-3" />
                  ) : (
                    <TrendingDown className="h-3 w-3" />
                  )}
                  {stock.change}
                </div>
              </div>
            </button>
          ))
        )}
      </div>
    </div>
  )
}

export type { Stock }
