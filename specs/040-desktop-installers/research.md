# 完整本地桌面安装版

## 范围

用户明确选择 Windows exe / macOS dmg 完整本地版，自带运行环境与数据库，
无需用户安装 Java、Node、Docker；首次启动配置模型。模型权重不随应用分发。
Java 主后端与共用前端继续复用；Go 生产候选门禁不变。

## 决策与替代方案

- **Electron 45.2.3 + electron-builder 26.18.0**：npm registry 核实的稳定版本。
  复用现有 Vue 界面，提供跨平台窗口、系统凭据加密、进程生命周期和 NSIS/DMG 构建。
  替代：jpackage 可内置 JRE，但仍需另写浏览器/窗口和首次配置；Tauri 需要新增
  Rust 和 WebView2 分发链；Docker 启动器不满足用户选定的独立安装要求。
- **Temurin JRE 21.0.13+9**：从 Adoptium 官方 API 核实资源，按平台固定 SHA-256，
  保留完整法律声明；不用本机 Oracle JDK 再分发。
- **pg0 0.16.1**：MIT，按平台固定二进制 SHA-256；内含 PostgreSQL 18.1 和 pgvector，
  首次启动本地解压，无需联网下载数据库。替代：Zonky/EDB 原生 PostgreSQL 仍需自行
  构建并分发每个平台的 pgvector；SQLite/PGlite 会改变后端驱动、SQL 或扩展语义。
  桌面使用全新独立数据目录，实际验收 V1–V49 在 PG18 的回放；不接管已有 PG17 实例。
- **回环 HTTP 静态服务 + API 流式代理**：同源复用现有浏览器客户端、SSE、附件和 SPA 路由，
  不改 API 业务代码。后端强制认证及 CSRF，随机凭据仅由主进程使用，自动登录的 HttpOnly
  Cookie 每次启动重新写入独立 Electron session；持久分区与优先复用本地端口保留界面偏好，阻止跨站 Origin/Host 和远程导航，禁用 Node
  集成，启用 sandbox/contextIsolation。配置 IPC 仅允许本地首次配置文档的主 frame。
- **系统用户数据目录**：配置经 safeStorage 加密；服务不继承开发环境的模型/数据库/Java
  覆盖变量；私有目录 POSIX 0700、文件0600。系统加密不可用时明确提示文件保护回退。
  退出先停后端，再用 pg_ctl 正常停库；永不调用 pg0 drop。

## 分发边界

本机为 Apple Silicon，可验证原生 macOS 包；Windows x64、Intel macOS 在对应 GitHub
Actions runner 构建和执行真实原生数据库/后端回归。没有平台签名证书时，安装包必须
如实标记未签名，不能宣称通过 Apple notarization 或 Windows 可信发布者验证。

## 官方资料

- https://www.electronjs.org/docs/latest/tutorial/security
- https://www.electronjs.org/docs/latest/api/safe-storage
- https://www.electron.build/
- https://adoptium.net/temurin/releases/
- https://github.com/vectorize-io/pg0/tree/v0.16.1
- https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html
