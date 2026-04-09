"use client"

import { useState, useEffect } from "react"
import { Activity, Cpu, Database, Zap, Server, Clock } from "lucide-react"
import { cn } from "@/lib/utils"

interface Metric {
  id: string
  label: string
  value: string
  unit: string
  icon: typeof Activity
  status: "good" | "warning" | "critical"
  trend?: "up" | "down" | "stable"
}

export function SystemMetrics() {
  const [metrics, setMetrics] = useState<Metric[]>([
    {
      id: "kafka-tps",
      label: "Kafka TPS",
      value: "12,450",
      unit: "msg/s",
      icon: Activity,
      status: "good",
      trend: "up",
    },
    {
      id: "rrcf-latency",
      label: "RRCF 지연시간",
      value: "2.3",
      unit: "ms",
      icon: Zap,
      status: "good",
      trend: "stable",
    },
    {
      id: "cpu-usage",
      label: "CPU 사용률",
      value: "34",
      unit: "%",
      icon: Cpu,
      status: "good",
      trend: "stable",
    },
    {
      id: "memory",
      label: "메모리",
      value: "6.2",
      unit: "GB",
      icon: Server,
      status: "good",
      trend: "stable",
    },
    {
      id: "db-queries",
      label: "DB 쿼리",
      value: "1,240",
      unit: "/min",
      icon: Database,
      status: "good",
      trend: "up",
    },
    {
      id: "uptime",
      label: "가동 시간",
      value: "99.97",
      unit: "%",
      icon: Clock,
      status: "good",
      trend: "stable",
    },
  ])

  useEffect(() => {
    const interval = setInterval(() => {
      setMetrics((prev) =>
        prev.map((metric) => {
          let newValue = metric.value
          let newStatus = metric.status
          let newTrend = metric.trend

          switch (metric.id) {
            case "kafka-tps": {
              const base = 12000 + Math.random() * 1000
              newValue = base.toLocaleString("ko-KR", {
                maximumFractionDigits: 0,
              })
              newTrend = Math.random() > 0.5 ? "up" : "stable"
              break
            }
            case "rrcf-latency": {
              const latency = 1.5 + Math.random() * 2
              newValue = latency.toFixed(1)
              newStatus = latency > 5 ? "warning" : "good"
              break
            }
            case "cpu-usage": {
              const cpu = 25 + Math.random() * 30
              newValue = cpu.toFixed(0)
              newStatus = cpu > 80 ? "critical" : cpu > 60 ? "warning" : "good"
              break
            }
            case "memory": {
              const mem = 5.5 + Math.random() * 2
              newValue = mem.toFixed(1)
              newStatus = mem > 14 ? "critical" : mem > 12 ? "warning" : "good"
              break
            }
            case "db-queries": {
              const queries = 1000 + Math.random() * 500
              newValue = queries.toLocaleString("ko-KR", {
                maximumFractionDigits: 0,
              })
              break
            }
          }

          return {
            ...metric,
            value: newValue,
            status: newStatus,
            trend: newTrend,
          }
        })
      )
    }, 2000)

    return () => clearInterval(interval)
  }, [])

  const statusColors = {
    good: "text-chart-1",
    warning: "text-chart-4",
    critical: "text-chart-2",
  }

  const statusBg = {
    good: "bg-chart-1/10",
    warning: "bg-chart-4/10",
    critical: "bg-chart-2/10",
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center justify-between border-b border-border px-4 py-2">
        <div className="flex items-center gap-2">
          <Cpu className="h-4 w-4 text-chart-3" />
          <h3 className="text-sm font-semibold text-foreground">
            시스템 메트릭
          </h3>
        </div>
        <div className="flex items-center gap-1.5">
          <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-chart-1" />
          <span className="text-xs text-muted-foreground">실시간</span>
        </div>
      </div>
      <div className="grid flex-1 grid-cols-2 gap-2 p-3 lg:grid-cols-3">
        {metrics.map((metric) => {
          const Icon = metric.icon
          return (
            <div
              key={metric.id}
              className={cn(
                "flex flex-col justify-between rounded-lg border border-border/50 p-3 transition-colors",
                statusBg[metric.status]
              )}
            >
              <div className="flex items-center justify-between">
                <Icon
                  className={cn("h-4 w-4", statusColors[metric.status])}
                />
                {metric.trend && (
                  <span
                    className={cn(
                      "text-[10px]",
                      metric.trend === "up" && "text-chart-1",
                      metric.trend === "down" && "text-chart-2",
                      metric.trend === "stable" && "text-muted-foreground"
                    )}
                  >
                    {metric.trend === "up" && "↑"}
                    {metric.trend === "down" && "↓"}
                    {metric.trend === "stable" && "→"}
                  </span>
                )}
              </div>
              <div className="mt-2">
                <div className="flex items-baseline gap-1">
                  <span
                    className={cn(
                      "text-lg font-bold tabular-nums",
                      statusColors[metric.status]
                    )}
                  >
                    {metric.value}
                  </span>
                  <span className="text-[10px] text-muted-foreground">
                    {metric.unit}
                  </span>
                </div>
                <span className="text-[10px] text-muted-foreground">
                  {metric.label}
                </span>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
