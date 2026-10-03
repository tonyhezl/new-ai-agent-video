import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

// 仅未登录访问的认证页面（已登录时重定向到 /entry）
const AUTH_PAGES = ["/login", "/register", "/setup", "/forgot-password"];

// 游客和登录用户均可访问的公开功能页面（已登录时不重定向）
const PUBLIC_FEATURE_PATHS = ["/workbench"];

const ALL_PUBLIC_PATHS = [...AUTH_PAGES, ...PUBLIC_FEATURE_PATHS];

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  // 静态资源和 API 路由不拦截
  if (
    pathname.startsWith("/_next") ||
    pathname.startsWith("/api") ||
    pathname.startsWith("/favicon") ||
    pathname.includes(".")
  ) {
    return NextResponse.next();
  }

  // 从 cookie 中读取认证状态（zustand persist 存在 localStorage，middleware 无法直接读取）
  // 使用一个轻量的 cookie 来同步认证状态
  const token = request.cookies.get("auth-token")?.value;
  const isAuthPage = AUTH_PAGES.some((path) => pathname.startsWith(path));
  const isPublicPath = ALL_PUBLIC_PATHS.some((path) => pathname.startsWith(path));
  const isRoot = pathname === "/";

  // 权限信息在客户端加载；先进入分流页，再选择首个有权访问的模块。
  if (token && isRoot) {
    return NextResponse.redirect(new URL("/entry", request.url));
  }

  // 根路径放行（未认证时需要客户端检测初始化状态）
  if (isRoot) {
    return NextResponse.next();
  }

  // 未认证访问受保护页面 → 重定向到登录
  if (!token && !isPublicPath) {
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("redirect", pathname);
    return NextResponse.redirect(loginUrl);
  }

  // 已认证访问认证页（登录/注册等） → 进入权限分流页
  if (token && isAuthPage) {
    return NextResponse.redirect(new URL("/entry", request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: [
    // 匹配所有路径，排除静态资源
    "/((?!_next/static|_next/image|favicon.ico).*)",
  ],
};
