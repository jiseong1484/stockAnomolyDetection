"use client"

import { useState } from "react"
import Link from "next/link"
import { Activity, Mail, Lock, ArrowRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { API_BASE_URL } from "@/lib/api"

import { useRouter } from "next/navigation"

export default function LoginPage() {
  const router = useRouter()
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setLoading(true)

    try {
      const response = await fetch(`${API_BASE_URL}/api/v1/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      })

      if (response.ok) {
        const data = await response.json()
        const token = `${data.tokenType} ${data.accessToken}`
        localStorage.setItem("accessToken", token)
        document.cookie = `accessToken=${encodeURIComponent(token)}; path=/; SameSite=Lax`
        router.push("/")
      } else {
        const errorMsg = await response.text()
        alert("로그인 실패: 이메일 또는 비밀번호를 확인해주세요.")
      }
    } catch (error) {
      console.error("Login Error:", error)
      alert("서버 연결에 실패했습니다.")
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4 py-12">
      <div className="w-full max-w-[400px] space-y-6">
        <div className="flex flex-col items-center gap-2 text-center">
          <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-primary">
            <Activity className="h-6 w-6 text-primary-foreground" />
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-foreground">
            Stock Anomaly Detection
          </h1>
          <p className="text-sm text-muted-foreground">
            계정에 로그인하여 실시간 모니터링을 시작하세요.
          </p>
        </div>

        <Card className="border-border/50 bg-secondary/20 shadow-xl backdrop-blur-sm">
          <CardHeader>
            <CardTitle className="text-xl">로그인</CardTitle>
            <CardDescription>이메일과 비밀번호를 입력해주세요.</CardDescription>
          </CardHeader>
          <form onSubmit={handleSubmit}>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="email">이메일</Label>
                <div className="relative">
                  <Mail className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    id="email"
                    type="email"
                    placeholder="name@example.com"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    className="pl-9"
                    required
                  />
                </div>
              </div>
              <div className="space-y-2">
                <Label htmlFor="password">비밀번호</Label>
                <div className="relative">
                  <Lock className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                  <Input
                    id="password"
                    type="password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="pl-9"
                    required
                  />
                </div>
              </div>
            </CardContent>
            <CardFooter className="flex flex-col gap-4">
              <Button type="submit" className="w-full" disabled={loading}>
                {loading ? "로그인 중..." : "로그인"}
                {!loading && <ArrowRight className="ml-2 h-4 w-4" />}
              </Button>
              <div className="text-center text-sm text-muted-foreground">
                계정이 없으신가요?{" "}
                <Link href="/auth/signup" className="font-medium text-primary hover:underline">
                  회원가입
                </Link>
              </div>
            </CardFooter>
          </form>
        </Card>
      </div>
    </div>
  )
}
