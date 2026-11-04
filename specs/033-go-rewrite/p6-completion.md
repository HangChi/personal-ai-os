# P6 知识、RAG 与后台运行验收

状态：实施中。T600–T609 必须逐批验证；当前报告不表示 P6 或发布门禁已完成。

## 第一批：文件解析与结构切分

新增 knowledge Reader 与 Markdown 切分器。UTF-8 严格校验、BOM、300 UTF-16 文件名/保扩展名、目录移除、大小/短文拒收、图片 MIME/无文本/上游错误均覆盖。DOCX 使用标准库 archive/zip 与 encoding/xml，读取正文段落和表格单元格，保留顺序；拒绝 ZIP bomb、超深 XML 与损坏输入。PDF 使用独立 Poppler pdfinfo/pdftotext，校验完整页数（最多 500 页），不截掉第 501 页；10 秒 context/进程退出和 64 MiB 输出上限，不构造 shell。无文本、加密和损坏错误保留分类。

Markdown 沿用原标题栈、代码围栏、120 字符章节前缀、段落累积、70%自然断点、重叠和 1.5 倍短块合并界限；另保证硬切不会制造半个 Unicode 码点。

实际执行：knowledge race 测试通过（6 个测试，含真实现场生成 PDF）；首批 25 个 Go 包全量 race 和固定基线 check 通过。模型调用装配、数据库写入、任务队列与真实 Java 差分待后续批次。
