import { NextResponse } from 'next/server'
import type { NextRequest } from 'next/server'

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl

  if (pathname.startsWith('/auth/login') || pathname.startsWith('/auth/signup')) {
    return NextResponse.next()
  }

  const isAuthenticated = request.cookies.get('authenticated')?.value === 'true'
  if (!isAuthenticated) {
    return NextResponse.redirect(new URL('/auth/login', request.url))
  }

  return NextResponse.next()
}

export const config = {
  matcher: ['/((?!api|_next/static|_next/image|favicon.ico).*)'],
}
