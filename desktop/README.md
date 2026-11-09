# Personal AI OS 完整本地桌面版

安装后自带 Electron、Java 21 和 PostgreSQL/pgvector，无需另外安装 Java、Node、Docker。
不附带模型权重：首次启动填写 OpenAI 兼容对话服务和输出 **1024 维**向量的嵌入服务。
可连接本机已有 Ollama，也可连接外部 API。模型未连接时，任务、日程等本地功能仍可使用。

## 安装与首次使用

- Windows 10/11 x64：运行 `Personal-AI-OS-0.1.1-win-x64.exe`，按向导安装。
- macOS Apple Silicon：打开 `Personal-AI-OS-0.1.1-mac-arm64.dmg`，将应用拖入 Applications。
- macOS Intel：使用 `Personal-AI-OS-0.1.1-mac-x64.dmg`。Intel 包的 pg0 上游构建要求 macOS 15+；
  Apple Silicon 包要求 macOS 14+。

首次配置的服务地址是 OpenAI 兼容 Base URL，需保留该服务要求的路径。
本机 Ollama 示例为 `http://127.0.0.1:11434/v1`，对话模型示例 `qwen2.5:7b`、
嵌入模型示例 `bge-m3`；模型需要已在 Ollama 中准备好。本应用不安装 Ollama 或下载模型。
如果使用云端模型，填写自己的端点、模型 ID 和 API Key。填写完成点击“保存并启动工作台”。
保存和启动不发送模型测试请求；进入后可在设置中主动测试，测试可能产生模型服务费用。

发布前请区分签名状态：没有发布者签名证书的包是**未受信签名的预览版**，macOS 仅使用
ad-hoc 签名保证应用包完整性，未通过 Apple notarization
或 Windows 可信发布者验证，操作系统可能阻止直接打开。不要把这些包描述为已签名。

## 数据与服务

- macOS：`~/Library/Application Support/Personal AI OS/`。
- Windows：`%APPDATA%/Personal AI OS/`。
- 菜单“工作台 → 打开数据目录 / 打开日志目录”可查看实际位置。
- 配置使用操作系统加密服务保存，数据库和 API 使用独立随机凭据。系统加密不可用时，
  首次配置页明确提示文件权限保护回退；Windows 继承用户配置目录 ACL，POSIX 目录0700/文件0600。
- 数据库与后端均只监听 `127.0.0.1`，动态分配端口。API 强制认证及 CSRF，登录由桌面主进程完成。
- 关闭最后一个窗口或退出应用，会先停后端，再正常停止本地数据库。异常退出后的重启保留原数据。
- 卸载程序保留用户数据。请定期在工作台的维护功能中导出业务备份；需要完整搬迁时，先退出应用，
  再保存整个用户数据目录。系统加密的配置不能直接跨电脑解密，换机时重新配置凭据。
- 桌面数据库为独立的 PostgreSQL 18.1，不接管或修改已有 Docker/PostgreSQL 17 实例。
  不可将已有物理数据库目录直接放入桌面目录；业务数据迁移使用应用支持的导入导出流程。
- “模型启动配置”会停止服务后打开配置页；修改嵌入模型会影响已有知识向量，
  即使维度相同也不能直接混用，需按知识库运维说明重建索引。

## 从源码构建

构建机需 Java 21、Node.js 22.18+、对应操作系统原生构建环境。终端从仓库根目录执行：

```bash
(cd backend && ./mvnw -B -DskipTests package)
(cd frontend && npm ci && npm run build)
(cd desktop && npm ci && npm test && npm run prepare:resources && npm run smoke)
(cd desktop && npm run dist)
(cd desktop && node scripts/verify-package.cjs && node scripts/stage-artifacts.cjs)
```

Windows 使用 Git Bash 或仓库 Maven Windows Wrapper。构建会联网下载固定版本工具和运行时，
分发后的应用首次启动不再下载数据库/JRE。下载来源与 SHA-256 固定于 `runtime-lock.json`。
本地资源输出 `desktop/resources/`，安装包输出 `desktop/dist/`，完整交付附件与检查结果输出
`desktop/artifacts/`。这些目录全部忽略，不提交用户数据或构建文件。

GitHub Actions 的 `Desktop Installers` 工作流分别在 Windows x64、Apple Silicon 与 Intel macOS
runner 构建，执行独立新数据库的启动/认证/迁移/模型桩/重启持久化/退出回归，再检验打包资源。
工作流只上传构建附件，不自动发布未经核验的 release。

桌面安装新增依赖与替代方案见 [设计决策](../specs/040-desktop-installers/research.md)，
第三方许可见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md)。
