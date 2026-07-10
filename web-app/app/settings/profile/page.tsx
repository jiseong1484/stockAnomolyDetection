"use client"

import { useState, useEffect } from "react"
import { useRouter } from "next/navigation"
import { Activity, ArrowLeft, Key, ShieldCheck, User, CheckCircle, XCircle } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { API_BASE_URL } from "@/lib/api"

interface UserProfile {
  email: string
  name: string
  phoneNumber: string
  hasKisApiKey: boolean
}

export default function ProfileSettingsPage() {
  const router = useRouter()
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [kisApiKey, setKisApiKey] = useState("")
  const [kisSecretKey, setKisSecretKey] = useState("")
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<{ type: "success" | "error"; text: string } | null>(null)

  useEffect(() => {
    const token = localStorage.getItem("accessToken")
    if (!token) {
      router.replace("/auth/login")
      return
    }
    fetchProfile(token)
  }, [router])

  const fetchProfile = async (token: string) => {
    try {
      const response = await fetch(`${API_BASE_URL}/api/v1/users/me`, {
        headers: { Authorization: token },
      })
      if (response.ok) {
        const data = await response.json()
        setProfile(data)
      } else if (response.status === 401) {
        router.replace("/auth/login")
      }
    } catch (error) {
      console.error("Failed to fetch profile:", error)
    } finally {
      setLoading(false)
    }
  }

  const handleSaveKisKeys = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!kisApiKey || !kisSecretKey) return

    setSaving(true)
    setMessage(null)

    try {
      const token = localStorage.getItem("accessToken")
      const response = await fetch(`${API_BASE_URL}/api/v1/users/me/kis-keys`, {
        method: "PUT",
        headers: {
          Authorization: token || "",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ kisApiKey, kisSecretKey }),
      })

      if (response.ok) {
        setMessage({ type: "success", text: "KIS API 키가 성공적으로 저장되었습니다." })
        setKisApiKey("")
        setKisSecretKey("")
        setProfile((prev) => prev ? { ...prev, hasKisApiKey: true } : prev)
      } else {
        const errorText = await response.text()
        setMessage({ type: "error", text: errorText || "저장에 실패했습니다. 다시 시도해주세요." })
      }
    } catch {
      setMessage({ type: "error", text: "서버 연결에 실패했습니다." })
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center bg-background">
        <Activity className="h-6 w-6 animate-pulse text-primary" />
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-background p-6">
      <div className="mx-auto max-w-2xl space-y-6">
        {/* Header */}
        <div className="flex items-center gap-4">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => router.push("/")}
            className="text-muted-foreground"
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <h1 className="text-xl font-bold text-foreground">프로필 설정</h1>
            <p className="text-sm text-muted-foreground">계정 정보 및 KIS API 키를 관리합니다.</p>
          </div>
        </div>

        {/* 계정 정보 */}
        <Card className="border-border bg-card">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <User className="h-4 w-4" />
              계정 정보
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-1">
                <p className="text-xs text-muted-foreground">이름</p>
                <p className="text-sm font-medium text-foreground">{profile?.name ?? "-"}</p>
              </div>
              <div className="space-y-1">
                <p className="text-xs text-muted-foreground">전화번호</p>
                <p className="text-sm font-medium text-foreground">{profile?.phoneNumber ?? "-"}</p>
              </div>
            </div>
            <div className="space-y-1">
              <p className="text-xs text-muted-foreground">이메일</p>
              <p className="text-sm font-medium text-foreground">{profile?.email ?? "-"}</p>
            </div>
          </CardContent>
        </Card>

        {/* KIS API 키 설정 */}
        <Card className="border-border bg-card">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Key className="h-4 w-4" />
              KIS API 키
            </CardTitle>
            <CardDescription>
              한국투자증권 OpenAPI에서 발급받은 키를 등록하면 실시간 주식 데이터를 수신할 수 있습니다.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            {/* 현재 등록 상태 */}
            <div className="flex items-center gap-2 rounded-lg border border-border bg-secondary/30 px-4 py-3">
              {profile?.hasKisApiKey ? (
                <>
                  <CheckCircle className="h-4 w-4 text-chart-1" />
                  <span className="text-sm text-chart-1 font-medium">API 키가 등록되어 있습니다.</span>
                </>
              ) : (
                <>
                  <XCircle className="h-4 w-4 text-destructive" />
                  <span className="text-sm text-destructive font-medium">API 키가 등록되지 않았습니다. 등록 후 실시간 구독이 가능합니다.</span>
                </>
              )}
            </div>

            {/* 키 수정 폼 */}
            <form onSubmit={handleSaveKisKeys} className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="kisApiKey">
                  {profile?.hasKisApiKey ? "App Key 변경" : "App Key"}
                </Label>
                <div className="relative">
                  <Key className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    id="kisApiKey"
                    placeholder="발급받은 App Key 입력"
                    value={kisApiKey}
                    onChange={(e) => setKisApiKey(e.target.value)}
                    className="pl-9"
                    required
                  />
                </div>
              </div>

              <div className="space-y-2">
                <Label htmlFor="kisSecretKey">
                  {profile?.hasKisApiKey ? "Secret Key 변경" : "Secret Key"}
                </Label>
                <div className="relative">
                  <ShieldCheck className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    id="kisSecretKey"
                    type="password"
                    placeholder="발급받은 Secret Key 입력"
                    value={kisSecretKey}
                    onChange={(e) => setKisSecretKey(e.target.value)}
                    className="pl-9"
                    required
                  />
                </div>
              </div>

              {message && (
                <div className={`rounded-lg px-4 py-3 text-sm ${
                  message.type === "success"
                    ? "bg-chart-1/10 text-chart-1"
                    : "bg-destructive/10 text-destructive"
                }`}>
                  {message.text}
                </div>
              )}

              <Button type="submit" className="w-full" disabled={saving}>
                {saving ? "저장 중..." : (profile?.hasKisApiKey ? "API 키 변경" : "API 키 등록")}
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
