# 织影 LoomFrame Web

织影前端应用，负责项目管理、剧本与分镜编辑、素材管理、Agent Pipeline 可视化和系统设置等界面能力。

完整部署说明请参考仓库根目录的 [本地运行与线上部署说明](../交付部署说明.md)。Docker 构建上下文为项目根目录，使用根目录 `compose.yaml`。

## 技术栈

- Next.js 16
- React 19
- TypeScript
- Tailwind CSS v4
- Zustand
- Axios

## 本地开发

```bash
pnpm install
pnpm dev
```

默认访问地址：<http://localhost:3000>

前端默认请求后端地址：<http://localhost:18080>

如需覆盖后端地址，可设置环境变量：

```bash
NEXT_PUBLIC_API_BASE_URL=http://localhost:18080
```

When the web app and API use different subdomains, configure a shared cookie domain so native media requests can authenticate:

```bash
NEXT_PUBLIC_AUTH_COOKIE_DOMAIN=.example.com
```

## 生产构建

```bash
pnpm build
pnpm start
```

`next.config.ts` 已启用 `standalone` 输出，便于容器化部署。

## 目录说明

- `app/`：App Router 页面与布局
- `components/`：业务组件与通用 UI 组件
- `lib/api/`：后端接口封装
- `lib/store/`：客户端状态管理
- `proxy.ts`：页面访问控制与登录跳转逻辑
