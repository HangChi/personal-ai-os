# HTTP 基础契约

> 本页记录模块契约及分阶段验证范围。当前启动/模块组合见[Go 文档中心](../../../docs/README.md)，两版最新能力与差异见[双端说明](../../../../docs/backend-parity.md)；下文固定基线、测试数和 P1/P3 等范围不是当前全产品或生产批准。

已有请求ID、模型路径/速率/并发中间件；这些能力需要显式组合，未放开诊断入口的业务API。

## 错误边界

`HandleErrors(Endpoint, logger)` 将处理器返回的显式错误变为Java兼容JSON信封。业务服务使用 `api.NewBusinessError(code, publicMessage)`，文案必须明确可公开；空文案保持为空，不自动替换。参数名/Violation字段来自代码定义，不能把请求值、SQL或原始异常消息填进去。

HTTP提供 `InvalidBody`、`MissingParameter`、`InvalidParameter`、`ValidationFailure`、`NotFound`、`MethodNotSupported`、`NotAcceptable`、`UploadTooLarge`、`IntegrityConflict`、`ClientDisconnected`。数据库完整性由仓储显式分类；边界不会从错误文本猜409。底层原因可 `errors.Is` 检查，但日志/响应不输出原因；最外层显式类型优先，多原因歧义失败关闭。

错误前置返回即使Accept为SSE也强制JSON，HEAD无body；真实全局405无Allow，日志保留支持方法、无查询串路径和请求ID。OPTIONS由具体路由处理Allow。日志不记录提交值、异常消息或panic堆栈。下载响应尚未提交时，错误会清除旧长度/压缩/附件头。

处理器必须在返回前完成ResponseWriter操作。1xx除101不算最终提交；写入/Flush/Hijack后返回error或panic时，用 `http.ErrAbortHandler` 中止连接，不能再写JSON污染SSE或文件。已建立流的业务错误仍应由业务层发送其协议事件。明确的客户端断连只记DEBUG、不再写入；普通context取消/上游超时不会自动当成断连。

适配器保留原有Flusher/Hijacker能力，支持ResponseController解包。未提供的可选接口不能因包装凭空出现。不得把该边界看作自动路由、请求解码、内容协商、multipart限制或认证链的替代。

## 真实Java对照

```bash
python3 scripts/migration/java_exception_fixture.py
```

从固定基线读取5个Java源文件，用实际BOM的离线依赖直接调用原处理器，核对36响应和405/断连日志；不启动Spring、不读.env或连接现有服务。默认仅核对，显式 `--write` 才更新快照。此证据只覆盖处理器，不覆盖Servlet分派、JSON绑定、验证提供器或完整业务安全链。

## Multipart基础解析

`ParseMultipart(w, request, MultipartLimits)`必须显式提供正数文件/总请求上限；`config.Properties.Multipart()`从canonical属性绑定内置8MB/10MB限制，Java的无界-1在Go明确拒绝。独立解析结果由调用方`Close()`清理；Foundation通过`MultipartLifecycle`持有一次惰性解析，CSRF正文token与`RequestMultipart(w,r)`共享结果，请求结束统一清理。文件`Open()`返回的reader先关闭，再清理整个请求随机暂存目录。失败/超限/取消自动清理。只读取当前请求，不调用业务或现有存储。

内容协商器解析带引号的多值Accept，按原Spring质量优先和稳定声明顺序选择，保留原q=0兼容行为。整条头包含非法媒体/q/未知字符集时拒绝，不能跳过坏项选另一个。保留profile等响应参数，支持实际UTF-8/UTF-16BE/LE/UTF-32BE/LE编码。普通MVC和显式produces的Actuator在有效但不支持的字符集、仅逗号Accept上的错误契约不同，真实差分分别验证。

空filename仍是文件、无filename是普通字段，Content-Type缺失为nil；保留Unicode/路径文本原文件名，绝不用它拼接磁盘路径。重复分片按到达顺序保存，单文件绑定取第一个。普通字段同样受单片上限；未知Content-Length及关闭boundary后的尾随字节仍受总请求限制。固定Tomcat默认50分片、每片512字节头限制已有真实边界对照；自定义Tomcat连接器扩展明确拒绝。

真实Servlet夹具在两个仅测试端点分别使用@RequestParam/@RequestPart验证152个基础上传场景，后续增加安全/CSRF/容器默认限制后累计844个基础HTTP场景。临时大小缩到64B/1024B验证精确边界，不修改原YAML；二进制内容/分块总量/取消/失败暂存清理与32路隔离另有race测试。生产代码没有注册fixture端点；统一CSRF与上传生命周期已接Foundation，location支持存在的绝对目录，非0threshold等未支持扩展显式拒绝。CSRF读取超限保留框架JSON413，控制器解析超限为业务40003。附件/知识库/音频业务按P4/P6迁移。
