"use client"

import { useState } from "react"
import Link from "next/link"
import { Activity, Mail, Lock, User, Phone, Key, ShieldCheck, ArrowRight } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { Separator } from "@/components/ui/separator"

import { useRouter } from "next/navigation"

export default function SignupPage() {
  const router = useRouter()
  const [formData, setFormData] = useState({
    email: "",
    password: "",
    name: "",
    phoneNumber: "",
    kisApiKey: "",
    kisSecretKey: "",
  })
  const [loading, setLoading] = useState(false)

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setFormData({ ...formData, [e.target.id]: e.target.value })
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setLoading(true)

    try {
      const response = await fetch("http://localhost:8080/api/v1/users/signup", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(formData),
      })

      if (response.ok) {
        alert("회원가입이 완료되었습니다! 로그인 해주세요.")
        router.push("/auth/login")
      } else {
        const errorData = await response.text()
        alert(errorData || "회원가입에 실패했습니다. 입력 정보를 확인해주세요.")
      }
    } catch (error) {
      console.error("Signup Error:", error)
      alert("서버 연결에 실패했습니다.")
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4 py-12">
      <div className="w-full max-w-[500px] space-y-6">
        <div className="flex flex-col items-center gap-2 text-center">
          <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-primary shadow-[0_0_20px_rgba(var(--primary),0.3)]">
            <Activity className="h-6 w-6 text-primary-foreground" />
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-foreground">
            Stock Anomaly Detection
          </h1>
          <p className="text-sm text-muted-foreground">
            실시간 주식 이상 탐지를 위한 회원가입을 시작하세요.
          </p>
        </div>

        <Card className="border-border/50 bg-secondary/20 shadow-xl backdrop-blur-sm overflow-hidden">
          <CardHeader>
            <CardTitle className="text-xl">회원가입</CardTitle>
            <CardDescription>사용자 정보 및 KIS API 키를 등록해주세요.</CardDescription>
          </CardHeader>
          <form onSubmit={handleSubmit}>
            <CardContent className="space-y-6">
              {/* Basic Info Section */}
              <div className="space-y-4">
                <div className="grid grid-cols-2 gap-4">
                  <div className="space-y-2">
                    <Label htmlFor="name">이름</Label>
                    <div className="relative">
                      <User className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                      <Input id="name" placeholder="홍길동" value={formData.name} onChange={handleChange} className="pl-9" required />
                    </div>
                  </div>
                  <div className="space-y-2">
                    <Label htmlFor="phoneNumber">전화번호</Label>
                    <div className="relative">
                      <Phone className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                      <Input id="phoneNumber" placeholder="010-1234-5678" value={formData.phoneNumber} onChange={handleChange} className="pl-9" />
                    </div>
                  </div>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="email">이메일</Label>
                  <div className="relative">
                    <Mail className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                    <Input id="email" type="email" placeholder="name@example.com" value={formData.email} onChange={handleChange} className="pl-9" required />
                  </div>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="password">비밀번호</Label>
                  <div className="relative">
                    <Lock className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                    <Input id="password" type="password" value={formData.password} onChange={handleChange} className="pl-9" required />
                  </div>
                </div>
              </div>

              <div className="flex items-center gap-4">
                <Separator className="flex-1" />
                <span className="text-[10px] font-bold uppercase tracking-widest text-muted-foreground">KIS API Configuration</span>
                <Separator className="flex-1" />
              </div>

              {/* KIS API Section */}
              <div className="space-y-4 bg-primary/5 p-4 rounded-lg border border-primary/10">
                <div className="space-y-2">
                  <Label htmlFor="kisApiKey">App Key</Label>
                  <div className="relative">
                    <Key className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                    <Input id="kisApiKey" placeholder="발급받은 App Key" value={formData.kisApiKey} onChange={handleChange} className="pl-9 bg-background/50" />
                  </div>
                </div>
                <div className="space-y-2">
                  <Label htmlFor="kisSecretKey">Secret Key</Label>
                  <div className="relative">
                    <ShieldCheck className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
                    <Input id="kisSecretKey" type="password" placeholder="발급받은 Secret Key" value={formData.kisSecretKey} onChange={handleChange} className="pl-9 bg-background/50" />
                  </div>
                </div>
              </div>
            </CardContent>
            <CardFooter className="flex flex-col gap-4 bg-secondary/30 p-6">
              <Button type="submit" className="w-full h-11" disabled={loading}>
                {loading ? "가입 진행 중..." : "가입하기"}
                {!loading && <ArrowRight className="ml-2 h-4 w-4" />}
              </Button>
              <div className="text-center text-sm text-muted-foreground">
                이미 계정이 있으신가요?{" "}
                <Link href="/auth/login" className="font-medium text-primary hover:underline">
                  로그인
                </Link>
              </div>
            </CardFooter>
          </form>
        </Card>
      </div>
    </div>
  )
}
