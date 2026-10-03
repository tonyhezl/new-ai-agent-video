# 织影 LoomFrame

本交付包含 Java 后端、Next.js 前端、数据库迁移和从源码构建的部署配置。

请先阅读 [本地运行与线上部署说明](交付部署说明.md)。文档包括 Docker 快速启动、本机源码开发、Linux 服务器部署、HTTPS、模型配置、数据备份和更新流程。

快速启动：安装 Docker Engine / Docker Desktop 和 Compose v2，在项目根目录复制 `.env.example` 为 `.env`，设置自己的 MySQL、Redis 密码，然后执行：

```bash
docker compose up -d --build
docker compose ps
```

访问 `http://localhost:8080`，按初始化页面创建管理员。模型和存储接口使用部署者自己的账号配置。

| 文件或目录 | 用途 |
| --- | --- |
| `ai--agent-video/` | Java 21 / Spring Boot 后端，内含 Maven Wrapper |
| `ai--agent-video-web/` | Next.js / React / TypeScript 前端，使用 pnpm |
| `compose.yaml` | 从交付源码构建并启动完整应用 |
| `compose.dev.yaml` | 本机开发时将 MySQL / Redis 端口映射到本机 |
| `.env.example` | 部署环境变量模板 |
| `docker/nginx/` | 应用反向代理及宿主机 HTTPS 配置示例 |
| `LICENSE` | 随交付物保留的许可与版权声明 |
