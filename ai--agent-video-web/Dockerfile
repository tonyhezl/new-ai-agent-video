# ============================================
# 织影 LoomFrame - 前端源码构建
# 构建上下文为项目根目录，由 compose.yaml 调用。
# ============================================

# ---------- 阶段 1: 安装依赖 ----------
FROM node:22-bookworm-slim AS deps

WORKDIR /build

COPY ai--agent-video-web/package.json ai--agent-video-web/pnpm-lock.yaml ai--agent-video-web/pnpm-workspace.yaml ./
RUN corepack enable pnpm
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile

# ---------- 阶段 2: 构建 ----------
FROM node:22-bookworm-slim AS builder

WORKDIR /build

COPY --from=deps /build/node_modules ./node_modules
COPY ai--agent-video-web/ .

# 构建时注入后端 API 地址（NEXT_PUBLIC_ 变量在 build 时烘焙进客户端 JS）
ARG NEXT_PUBLIC_API_BASE_URL=
ENV NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}
ENV NEXT_TELEMETRY_DISABLED=1

RUN corepack enable pnpm
RUN --mount=type=cache,target=/build/.next/cache pnpm build --webpack

# ---------- 阶段 3: 运行 ----------
FROM node:22-bookworm-slim

WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV HOSTNAME=0.0.0.0
ENV PORT=3000

# 复制构建产物
COPY --from=builder /build/.next/standalone ./
COPY --from=builder /build/.next/static ./.next/static
COPY --from=builder /build/public ./public
COPY LICENSE ./LICENSE

EXPOSE 3000

CMD ["node", "server.js"]
