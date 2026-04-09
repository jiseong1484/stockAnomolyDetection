import { NextResponse } from 'next/server'
import type { NextRequest } from 'next/server'

export function middleware(request: NextRequest) {
  // 로그인이나 회원가입 페이지는 예외 처리
  if (
    request.nextUrl.pathname.startsWith('/auth/login') ||
    request.nextUrl.pathname.startsWith('/auth/signup')
  ) {
    return NextResponse.next()
  }

  // Next.js 미들웨어에서는 localStorage에 직접 접근할 수 없으므로 (서버 사이드 환경)
  // 쿠키를 사용하여 토큰을 관리하거나, 클라이언트 사이드 리다이렉트가 필요합니다.
  // 우선은 프로젝트 구조상 클라이언트 사이드에서 처리하도록 두되, 
  // 여기서는 기본적인 정적 리소스 필터링만 해줍니다.
  
  return NextResponse.next()
}

export const config = {
  matcher: ['/((?!api|_next/static|_next/image|favicon.ico).*)'],
}
