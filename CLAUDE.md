# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

# Claude Code Instructions

- `AGENTS.md` 是本仓库的共享规则源；更新长期规则时优先改 `AGENTS.md`，不要在这里复制一份。
- 本文件只保留 Claude Code 专属入口和加载提示，避免根目录上下文膨胀。
- 目录细则在 `.claude/rules/`，处理匹配文件前先读取对应规则。
- 个人偏好和临时调试结论放 `CLAUDE.local.md` 或 Claude Memory，不要提交到仓库。

## Path Rules

- Backend: `.claude/rules/backend.md`
- AI / async / rate limit: `.claude/rules/ai-and-async.md`
- Frontend: `.claude/rules/frontend.md`

## 本地开发事实（不易从代码推断，猜错会浪费时间）

- 单模块 Gradle（`settings.gradle` 只含 `app`）：所有任务带 `:app:` 前缀，运行单个测试用 `./gradlew :app:test --tests "…"`。
- `bootRun` 会自动解析根目录 `.env` 并注入环境变量（Gradle 进程默认不读 `.env`），本地缺 API Key 先检查 `.env`。
- 前端 `pnpm run build` 等价于 `tsc && vite build`（含类型检查）；另有 `node --test` 单测（`package.json` 的 `test:*` 脚本）和 Playwright `test:e2e`。
- 重大架构改动前先看 `docs/` 是否已有对应设计文档（如 `voice-interview-architecture.md`、`testing/*.tdd.md`），保持一致并更新。
- push 到 `master` 会触发 GitHub Actions 构建镜像并部署腾讯云；commit message 遵循 `.githooks/commit-msg` 规范（Conventional Commits + 中文标题与正文，正文每行 `- ` 开头）。

## Maintenance

- 新增规则前先判断：删掉这条后，Claude 是否更容易犯同类错误。
- 能被测试、格式化、Hook 或 CI 强制的规则，不要只写成自然语言。
- 如果同一条规则反复被忽略，优先精简规则文件，而不是继续加粗或加感叹号。
