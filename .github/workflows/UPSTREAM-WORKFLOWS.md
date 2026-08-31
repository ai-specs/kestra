# 上游继承工作流：停用说明与重新启用指引

> 本文档是给后来接手的 agent / 维护者看的。`.github/workflows/` 下有大量从上游
> `kestra-io/kestra` 继承的工作流，它们在本仓库（`ai-specs/kestra` fork）中**已被统一停用**。
> 在改动代码前，请先读完本文，搞清楚「哪些工作流被停用、为什么停用、什么时候该把它们重新打开」。

---

## 1. 本仓库的工作流现状

本仓库只依赖 **3 个保持启用** 的工作流来维护产物：

| 工作流 | 文件 | 用途 |
|---|---|---|
| dsh Build and Push Base Image | `dsh-push-base-image.yml` | 构建并推送 `kestra-base` 基础镜像（JRE 25 + uv，含/不含 Python）到 `ghcr.io/ai-specs/kestra-base` |
| dsh Release to GHCR | `dsh-release-ghcr.yml` | 纯源码构建并发布 kestra 镜像（`BASE_IMAGE = kestra-base:latest`）到 `ghcr.io/ai-specs/kestra`。concurrency：tag 触发每个 tag 独立并行（`cancel-in-progress: false`）；非 tag（如 workflow_dispatch 选 develop）同组 `cancel-in-progress: true`，新任务自动取代旧任务（2026-08-31 用户授权） |
| Publish docker | `release-docker.yml` | 手动触发，复用上游发布 Docker 的可复用工作流（保留，未停用） |

**其余 21 个上游继承工作流全部在 GitHub Actions 页面被停用（Disabled）**，列表见第 3 节。
每个文件头部也加了 `NOTE (dsh)` 注释块指向本文档。

> **注意（2026-08-31 更新）**：21 个停用工作流中的 `pre-release.yml` 已被**裁剪为 test-only**
> （`78dc6e6fe2`：移除依赖缺失 secrets 的 `build-artifacts` / `publish-maven` /
> `generate-configuration-schema` / `publish-github` 等发布 job，仅保留 backend / frontend /
> design-system 测试与 otel 收尾）。因此若重新启用 Pre Release，它只会跑测试、不会因缺 secrets
> 失败；其余 20 个上游工作流未裁剪，重新启用后仍可能因缺 secrets 在运行期失败，需先补 secrets。

## 2. 为什么停用

- **产物链不依赖它们**：本仓库只产出镜像（base + kestra），上游那套完整 CI（后端/前端/设计系统测试、E2E、SDK 生成、发 develop 版 Maven/Docker、EE 联动、CodeQL、依赖提交等）在 fork 里没有人消费产物。
- **缺 secrets 跑不完整**：它们依赖上游专属 secrets（`GH_BOT_APP_ID`、`KESTRA_CI_EEBUILD_WEBHOOK_URL`、`SONATYPE_*`、`GCP_SERVICE_ACCOUNT`、`SLACK_WEBHOOK_URL` 等）和 `kestra-io/actions` 可复用工作流，在 fork 中即使启动也会在运行时失败。
- **曾造成大量 startup_failure**：`main-build.yml` 调用可复用工作流 `kestra-oss-backend-tests.yml@main` 时请求写权限（`checks/contents/pull-requests: write`），而仓库默认 workflow 权限是只读，导致整个 workflow 在解析阶段判为无效（`Invalid workflow file` → `Startup failure`）。**已于 2026-08-31 将仓库默认 workflow 权限改为 `Read and write permissions`** 解决了解析问题，但运行仍会因缺 secrets 失败，所以选择停用而非让它们反复失败。
- **停用 ≠ 删除**：停用状态存在仓库设置里，不修改文件内容，不会与上游同步产生冲突，可随时恢复。

## 3. 已停用的工作流清单（21 个）

| 工作流 | 文件 | 上游用途 |
|---|---|---|
| Main Workflow | `main-build.yml` | 上游主 CI：后端/前端/设计系统测试 + 发布 develop 版 Docker/Maven + schema 生成 |
| E2E tests scheduling | `e2e-scheduling.yml` | 每天 + 每次 push develop 跑 kestra E2E 套件 |
| Pull Request Workflow | `pull-request.yml` | PR 触发的主 CI |
| Pull Request - Delete Docker | `pull-request-cleanup.yml` | PR 关闭后清理镜像 |
| Pre Release | `pre-release.yml` | tag 触发、发 pre-release；**已裁剪为 test-only**（2026-08-31，仅保留测试 job，见第 1 节说明） |
| Start release | `global-start-release.yml` | 创建 release 流程入口 |
| Create new release branch | `global-create-new-release-branch.yml` | 创建 release 分支 |
| Build and Push Base Image (global) | `global-push-base-image.yml` | 上游自己的 base 镜像构建（与 dsh 版重复，保留 dsh 版） |
| Update Generated SDK | `update-generated-sdk.yml` | develop push 后重新生成 SDK |
| Sync slot-contracts to artifact-sdk | `sync-slot-contracts.yml` | slot-contracts 变更时同步 |
| Update plugin catalog count and create PR | `update-plugin-count.yml` | 更新插件计数并建 PR |
| Auto-Translate UI keys and create PR | `auto-translate-ui-keys.yml` | UI key 自动翻译 |
| EE compile check via Kestra webhook (manual) | `ee-compile-check-manual.yml` | 手动触发 EE 编译检查 |
| EE OpenAPI result - comment on OSS PR | `ee-openapi-result.yml` | EE OpenAPI 结果回写 PR |
| CodeQL | `codeql-analysis.yml` | 代码安全扫描 |
| Codespell | `codespell.yml` | 拼写检查 |
| Dependency Submission | `dependency-submission.yml` | 提交依赖图谱给 GitHub |
| Vulnerabilities Checks | `vulnerabilities-check.yml` | 漏洞检查 |
| Publish Design System | `publish-design-system.yml` | 发布设计系统包 |
| Release hey-api-plugin | `publish-hey-api-plugin.yml` | 发布 hey-api 插件 |
| Contributor Onboarding | `welcome.yml` | 新贡献者欢迎信息 |

## 4. 什么时候应该重新启用这些工作流

**当本仓库代码相对上游发生大的变化时，应重新启用相关（或全部）停用的工作流，跑通完整流程后再按需停用。** 典型触发场景：

- 同步 / 合并了上游的大版本改动（例如升级 Kestra 主版本、引入新的 breaking change）；
- 升级了 Java / Node / Gradle / 前端依赖等构建链组件；
- 修改了 `Dockerfile` / `Dockerfile.base` / 镜像构建链（此时尤其要开 `main-build` 的 `publish-develop-docker` 和 `e2e-scheduling` 验证镜像产物）；
- 需要跑回归测试、验证 EE 兼容性、重新生成 SDK / schema / 插件清单等派生产物；
- 上游新增了与本仓库相关的关键工作流。

## 5. 如何重新启用 / 停用

- **GitHub 页面**：Actions → 对应 workflow → `Enable workflow`（或 `Disable workflow`）。
- **CLI**：
  ```bash
  gh workflow enable main-build.yml
  gh workflow disable main-build.yml
  ```
- 注意：停用/启用是仓库设置，不产生 commit；修改 workflow 文件本身不会改变启用状态。
- 重新启用前建议先确认所需 secrets 已配置（`Settings → Secrets and variables → Actions`），否则即使启用也会在运行时失败。

## 6. 背景链接

- 2026-08-31：`main-build.yml` 连续 3 次 `Startup failure`（Invalid workflow file），根因见第 2 节；同日将仓库默认 workflow 权限改为 `Read and write permissions`，并在 Actions 页面停用上述 21 个工作流。
- 2026-08-31（晚些）后续演进：
  - `pre-release.yml` 裁剪为 test-only（`78dc6e6fe2`，drop secret-dependent publish jobs）；
  - `dsh-release-ghcr.yml` 新增按触发来源分流的 concurrency（`c9393ab041`，用户授权）：tag 并行不取消、非 tag（develop）新任务取代旧任务；
  - `plugin-modal` submodule 移除为并行 agent 有意为之并保留（`75b010aa0d` 说明 commit）。
