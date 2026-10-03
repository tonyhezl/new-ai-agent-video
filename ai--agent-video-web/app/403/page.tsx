"use client";

import Link from "next/link";
import { ShieldX } from "lucide-react";

export default function ForbiddenPage() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-background">
      <div className="text-center space-y-4">
        <ShieldX className="h-16 w-16 mx-auto text-muted-foreground" />
        <h1 className="text-2xl font-bold">403 - 无权限</h1>
        <p className="text-muted-foreground">您没有权限访问此页面，请联系管理员。</p>
        <Link
          href="/entry"
          className="inline-flex items-center justify-center rounded-lg bg-primary px-6 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
        >
          返回可访问模块
        </Link>
      </div>
    </div>
  );
}
