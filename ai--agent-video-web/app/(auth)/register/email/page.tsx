"use client";

import { Suspense, useState, useEffect, useRef } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import { cn } from "@/lib/utils";
import { AuthLayout } from "@/components/ui/auth-layout";
import { useAuthStore } from "@/lib/store/auth-store";
import { setAuthCookie } from "@/lib/api/client";
import { sendEmailCode, registerByEmail } from "@/lib/api/email-auth";

function EmailRegisterContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const redirect = searchParams.get("redirect") || "/workbench";

  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const [showSuccess, setShowSuccess] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, []);

  const startCountdown = (seconds: number) => {
    setCountdown(seconds);
    if (timerRef.current) clearInterval(timerRef.current);
    timerRef.current = setInterval(() => {
      setCountdown((prev) => {
        if (prev <= 1) {
          if (timerRef.current) clearInterval(timerRef.current);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
  };

  const handleSendCode = async () => {
    if (!email.trim() || countdown > 0 || sendingCode) return;
    setError("");
    setSendingCode(true);
    try {
      await sendEmailCode(email.trim(), "REGISTER");
      startCountdown(60);
    } catch (err) {
      setError(err instanceof Error ? err.message : "验证码发送失败");
    } finally {
      setSendingCode(false);
    }
  };

  const isFormValid =
    email.trim().length > 0 &&
    code.trim().length > 0 &&
    password.length >= 6 &&
    password === confirmPassword;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!isFormValid) return;

    setError("");
    setLoading(true);

    try {
      const resp = await registerByEmail(email.trim(), code.trim(), password);

      useAuthStore.setState({
        token: resp.accessToken,
        refreshToken: resp.refreshToken,
        user: {
          id: resp.userId,
          username: resp.username,
          nickname: resp.nickname,
          avatar: null,
          email: email.trim(),
          phone: null,
          status: 1,
          createTime: "",
          roles: ["user"],
        },
      });

      setAuthCookie(resp.accessToken);
      setShowSuccess(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "注册失败，请稍后重试");
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthLayout
      showSuccess={showSuccess}
      successTitle="注册成功"
      successSubtitle="正在进入工作台"
      onTransitionComplete={() => router.replace(redirect)}
    >
      <div className="space-y-2">
        <h1 className="text-[2rem] font-bold leading-[1.1] tracking-tight text-foreground">
          邮箱注册
        </h1>
        <p className="text-base text-muted-foreground font-light">
          使用邮箱验证码快速创建账号
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-3">
        <input
          type="email"
          placeholder="邮箱地址"
          value={email}
          onChange={(e) => {
            setEmail(e.target.value);
            setError("");
          }}
          className="w-full text-foreground border border-border rounded-lg py-3 px-4 focus:outline-none focus:border-primary/50 bg-background placeholder:text-muted-foreground/50 transition-colors"
          required
          autoComplete="email"
          disabled={loading}
        />

        <div className="flex gap-2">
          <input
            type="text"
            placeholder="验证码"
            value={code}
            onChange={(e) => {
              setCode(e.target.value);
              setError("");
            }}
            className="flex-1 text-foreground border border-border rounded-lg py-3 px-4 focus:outline-none focus:border-primary/50 bg-background placeholder:text-muted-foreground/50 transition-colors"
            required
            disabled={loading}
            maxLength={6}
          />
          <button
            type="button"
            onClick={handleSendCode}
            disabled={!email.trim() || countdown > 0 || sendingCode}
            className={cn(
              "shrink-0 rounded-lg px-4 text-sm font-medium transition-all whitespace-nowrap",
              countdown > 0 || !email.trim() || sendingCode
                ? "bg-muted text-muted-foreground border border-border/40 cursor-not-allowed"
                : "bg-primary/10 text-primary border border-primary/30 hover:bg-primary/20"
            )}
          >
            {sendingCode
              ? "发送中..."
              : countdown > 0
              ? `${countdown}s`
              : "发送验证码"}
          </button>
        </div>

        <input
          type="password"
          placeholder="密码（至少6位）"
          value={password}
          onChange={(e) => {
            setPassword(e.target.value);
            setError("");
          }}
          className="w-full text-foreground border border-border rounded-lg py-3 px-4 focus:outline-none focus:border-primary/50 bg-background placeholder:text-muted-foreground/50 transition-colors"
          required
          autoComplete="new-password"
          minLength={6}
          disabled={loading}
        />

        <input
          type="password"
          placeholder="确认密码"
          value={confirmPassword}
          onChange={(e) => {
            setConfirmPassword(e.target.value);
            setError("");
          }}
          className="w-full text-foreground border border-border rounded-lg py-3 px-4 focus:outline-none focus:border-primary/50 bg-background placeholder:text-muted-foreground/50 transition-colors"
          required
          autoComplete="new-password"
          minLength={6}
          disabled={loading}
        />

        <AnimatePresence>
          {error && (
            <motion.div
              initial={{ opacity: 0, height: 0 }}
              animate={{ opacity: 1, height: "auto" }}
              exit={{ opacity: 0, height: 0 }}
              transition={{ duration: 0.2 }}
              className="overflow-hidden"
            >
              <p className="text-red-400/90 text-sm py-1">{error}</p>
            </motion.div>
          )}
        </AnimatePresence>

        <motion.button
          type="submit"
          disabled={loading || !isFormValid}
          className={cn(
            "w-full rounded-lg font-medium py-3 transition-all duration-300",
            loading || !isFormValid
              ? "bg-muted text-muted-foreground border border-border/40 cursor-not-allowed"
              : "bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer"
          )}
          whileHover={!loading && isFormValid ? { scale: 1.02 } : undefined}
          whileTap={!loading && isFormValid ? { scale: 0.98 } : undefined}
          transition={{ duration: 0.2 }}
        >
          {loading ? (
            <span className="flex items-center justify-center gap-2">
              <svg className="animate-spin h-4 w-4" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
              </svg>
              注册中...
            </span>
          ) : (
            "注册并开始使用"
          )}
        </motion.button>
      </form>

      <p className="text-sm text-muted-foreground pt-2">
        已有账号？{" "}
        <Link href="/login" className="text-foreground underline decoration-border underline-offset-4 hover:decoration-primary transition-colors">
          去登录
        </Link>
      </p>

      <p className="text-xs text-muted-foreground/60 pt-4">织影 LoomFrame · 邮箱注册</p>
    </AuthLayout>
  );
}

export default function EmailRegisterPage() {
  return (
    <Suspense fallback={<div className="min-h-screen bg-background" />}>
      <EmailRegisterContent />
    </Suspense>
  );
}
