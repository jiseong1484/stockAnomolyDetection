"use client"

import { useState, useEffect } from "react"
import { useRouter } from "next/navigation"
import { Activity, Bell, Settings, Search, LogOut, User } from "lucide-react"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { StockList, type Stock } from "@/components/dashboard/stock-list"
import { PriceChart } from "@/components/dashboard/price-chart"
import { AlarmLog, type AlarmEntry } from "@/components/dashboard/alarm-log"
import { SystemMetrics } from "@/components/dashboard/system-metrics"
import { cn } from "@/lib/utils"

export default function DashboardPage() {
  const router = useRouter()
  const [selectedStock, setSelectedStock] = useState<Stock | null>(null)
  const [loading, setLoading] = useState(true)
  const [stocks, setStocks] = useState<Stock[]>([])
  const [alarms, setAlarms] = useState<AlarmEntry[]>([])
  const [newTicker, setNewTicker] = useState("")
  const [searchResults, setSearchResults] = useState<Stock[]>([])
  const [showSearchResults, setShowSearchResults] = useState(false)

  const fetchSubscriptions = async () => {
    const token = localStorage.getItem("accessToken")
    console.log("[Dashboard] Fetching subscriptions with token:", token ? "Exists" : "Missing")
    try {
      const response = await fetch("http://localhost:8080/api/v1/subscriptions", {
        headers: { "Authorization": token || "" }
      })
      if (response.ok) {
        const data = await response.json()
        setStocks(data.map((item: any) => ({
          id: item.ticker,
          ticker: item.ticker,
          name: item.name,
          price: item.price && item.price !== "0" ? item.price : "0",
          change: "0.00%",
          changeAmount: "0",
          status: "stable"
        })))
      } else {
        console.error("[Dashboard] Subscriptions fetch failed:", response.status)
      }
    } catch (error) {
      console.error("Failed to fetch subscriptions:", error)
    }
  }

  useEffect(() => {
    const searchStocks = async () => {
      if (newTicker.length < 2) {
        setSearchResults([])
        setShowSearchResults(false)
        return
      }

      try {
        const token = localStorage.getItem("accessToken")
        const response = await fetch(`http://localhost:8080/api/v1/stocks/search?query=${newTicker}`, {
          headers: { "Authorization": token || "" }
        })
        if (response.ok) {
          const data = await response.json()
          setSearchResults(data.map((item: any) => ({
            id: item.ticker,
            ticker: item.ticker,
            name: item.name,
            price: item.currentPrice || "0",
            change: "0.00%",
            changeAmount: "0",
            status: "stable"
          })))
          setShowSearchResults(true)
        }
      } catch (error) {
        console.error("Failed to search stocks:", error)
      }
    }

    const timer = setTimeout(searchStocks, 300)
    return () => clearTimeout(timer)
  }, [newTicker])

  useEffect(() => {
    const token = localStorage.getItem("accessToken")
    if (!token) {
      router.replace("/auth/login")
      return
    }
    setLoading(false)
    fetchSubscriptions()

    // SSE 연결 (Bearer 제외한 순수 토큰만 추출)
    const rawToken = token.startsWith("Bearer ") ? token.split(' ')[1] : token
    console.log("[Dashboard] Connecting SSE with token length:", rawToken.length)
    
    const eventSource = new EventSource(`http://localhost:8080/api/v1/sse/subscribe?token=${rawToken}`)

    eventSource.addEventListener("stock-tick", (event) => {
      const newTick = JSON.parse(event.data)
      const rate   = newTick.changeRate   != null ? String(newTick.changeRate)   : null
      const amount = newTick.changeAmount != null ? String(newTick.changeAmount) : null

      setStocks((prevStocks) =>
        prevStocks.map(stock => {
          if (stock.ticker !== newTick.ticker) return stock
          const update: Partial<typeof stock> = { price: String(newTick.price) }
          if (rate   != null) update.change       = rate + "%"
          if (amount != null) update.changeAmount = amount
          return { ...stock, ...update }
        })
      )
      setSelectedStock((prev) => {
        if (!prev || prev.ticker !== newTick.ticker) return prev
        const update: Partial<typeof prev> = { price: String(newTick.price) }
        if (rate   != null) update.change       = rate + "%"
        if (amount != null) update.changeAmount = amount
        return { ...prev, ...update }
      })
    })

    eventSource.addEventListener("anomaly-alert", (event) => {
      const raw = JSON.parse(event.data)
      const newAlarm: AlarmEntry = {
        id: `${raw.ticker}-${raw.timestamp_ms}`,
        timestamp: new Date(raw.timestamp_ms),
        ticker: raw.ticker,
        zscore: raw.zscore,
        price_change_pct: raw.price_change_pct,
        current_price: raw.current_price,
        severity: raw.severity,
      }
      setAlarms((prev) => [newAlarm, ...prev.slice(0, 49)])
    })

    return () => {
      eventSource.close()
    }
  }, [router])

  const handleSubscribe = async (tickerToAdd?: string) => {
    const targetTicker = tickerToAdd || newTicker
    if (!targetTicker) return
    const token = localStorage.getItem("accessToken")
    try {
      const response = await fetch("http://localhost:8080/api/v1/subscriptions", {
        method: "POST",
        headers: { 
          "Authorization": token || "",
          "Content-Type": "application/json"
        },
        body: JSON.stringify({ ticker: targetTicker })
      })
      if (response.ok) {
        setNewTicker("")
        setShowSearchResults(false)
        fetchSubscriptions()
      }
    } catch (error) {
      console.error("Failed to subscribe:", error)
    }
  }

  const handleLogout = async () => {
    const token = localStorage.getItem("accessToken")
    if (token) {
      await fetch("http://localhost:8080/api/v1/auth/logout", {
        method: "POST",
        headers: { "Authorization": token }
      }).catch(() => {})
    }
    localStorage.removeItem("accessToken")
    document.cookie = "accessToken=; path=/; max-age=0"
    router.replace("/auth/login")
  }

  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center bg-background text-foreground">
        <div className="flex flex-col items-center gap-4">
          <Activity className="h-8 w-8 animate-pulse text-primary" />
          <p className="text-sm font-medium">인증 정보를 확인 중입니다...</p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex h-screen flex-col bg-background">
      {/* Header */}
      <header className="flex h-14 items-center justify-between border-b border-border px-6">
        <div className="flex items-center gap-3">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary">
            <Activity className="h-4 w-4 text-primary-foreground" />
          </div>
          <div>
            <h1 className="text-sm font-bold tracking-tight text-foreground">
              Stock Anomaly Detection
            </h1>
            <p className="text-[10px] text-muted-foreground">
              Real-time RRCF Monitoring System
            </p>
          </div>
        </div>

        <div className="flex items-center gap-4">
          {/* Search / Add Stock */}
          <div className="relative hidden md:flex items-center gap-2">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <input
                type="text"
                placeholder="종목명 또는 코드 검색"
                value={newTicker}
                onChange={(e) => setNewTicker(e.target.value)}
                onFocus={() => newTicker.length >= 2 && setShowSearchResults(true)}
                className="h-9 w-64 rounded-lg border border-border bg-secondary/50 pl-9 pr-4 text-sm text-foreground placeholder:text-muted-foreground focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
              />
              {showSearchResults && searchResults.length > 0 && (
                <div className="absolute top-full left-0 z-50 mt-1 w-full rounded-md border border-border bg-popover p-1 shadow-md max-h-64 overflow-y-auto">
                  {searchResults.map((result) => (
                    <button
                      key={result.ticker}
                      className="flex w-full flex-col items-start rounded-sm px-3 py-2 text-sm hover:bg-accent hover:text-accent-foreground"
                      onClick={() => handleSubscribe(result.ticker)}
                    >
                      <span className="font-medium">{result.name}</span>
                      <span className="text-xs text-muted-foreground">{result.ticker}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div className="hidden items-center gap-2 rounded-lg bg-chart-1/10 px-3 py-1.5 sm:flex">
            <span className="relative flex h-2 w-2">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-chart-1 opacity-75" />
              <span className="relative inline-flex h-2 w-2 rounded-full bg-chart-1" />
            </span>
            <span className="text-xs font-medium text-chart-1">시스템 정상</span>
          </div>

          <div className="flex items-center gap-2">
            <Button variant="ghost" size="icon" className="text-muted-foreground">
              <Bell className="h-4 w-4" />
            </Button>
            
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon" className="text-muted-foreground">
                  <Settings className="h-4 w-4" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel>내 설정</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem>
                  <User className="mr-2 h-4 w-4" />
                  <span>프로필 설정</span>
                </DropdownMenuItem>
                <DropdownMenuItem onClick={handleLogout} className="text-destructive focus:text-destructive">
                  <LogOut className="mr-2 h-4 w-4" />
                  <span>로그아웃</span>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>

            <div className="h-8 w-8 rounded-full bg-secondary border border-border" />
          </div>
        </div>
      </header>

      <main className="flex-1 overflow-hidden p-6">
        <div className="grid h-full grid-cols-12 gap-6">
          {/* Left Column: Stock List */}
          <div className="col-span-12 lg:col-span-3 overflow-hidden rounded-xl border border-border bg-card shadow-sm">
            <StockList 
              stocks={stocks} 
              selectedStock={selectedStock} 
              onSelectStock={setSelectedStock} 
            />
          </div>

          {/* Center Column: Chart & Metrics */}
          <div className="col-span-12 lg:col-span-6 flex flex-col gap-6 overflow-hidden">
            <div className="flex-1 overflow-hidden rounded-xl border border-border bg-card shadow-sm">
              <PriceChart selectedStock={selectedStock} />
            </div>
            <div className="h-48 overflow-hidden rounded-xl border border-border bg-card shadow-sm p-4">
              <SystemMetrics />
            </div>
          </div>

          {/* Right Column: Logs */}
          <div className="col-span-12 lg:col-span-3 overflow-hidden rounded-xl border border-border bg-card shadow-sm">
            <AlarmLog alarms={alarms} />
          </div>
        </div>
      </main>
    </div>
  )
}
