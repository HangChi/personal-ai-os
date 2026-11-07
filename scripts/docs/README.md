# 文档维护工具

从仓库根目录使用 Python 3，无第三方依赖，不访问网络、数据库或模型：

```bash
python3 scripts/docs/generate_routes.py
python3 scripts/docs/generate_routes.py --check
python3 scripts/docs/check_docs.py
```

- `generate_routes.py` 读取 Java Controller 的类/方法 mapping，生成 [HTTP 路由参考](../../docs/reference/http-routes.md)。保留配置占位符，显式支持常规字符串/数组路径；不支持的表达式报错，不猜测。它不是 Java AST/OpenAPI 工具，不包含框架动态路由。
- `--check` 只比较生成结果，不写文件；Controller 改动后重新生成。
- `check_docs.py` 检查根 README/贡献/安全、`docs/`、两版入口、Go 模块 README 和评测/文档工具说明的本地 Markdown 链接、图片和标题锚点；忽略代码围栏与外部 URL。标题锚点按常用 GitHub 规则近似处理，不是完整 Markdown 渲染器。
- 工具检查比较当前 Java `@Tool(name=...)`、Go 三份内置 Schema/可选 searchWeb 与文档表格名称，不能证明参数/业务行为等价。
- 迁移检查验证当前连续版本、固定 Java 基线 SQL 哈希、Go 无第二份版本 SQL，并检查主要入口的当前版本范围。它不执行迁移，也不替代 Go/Flyway 集成验证。

`specs/` 与 `scripts/e2e/reports/` 属于历史规格/证据，只检查导航入口，不批量重写或扫描原报告；它们的链接仍可被当前文档引用。[文档中心](../../docs/README.md)说明当前与历史内容的分工。
