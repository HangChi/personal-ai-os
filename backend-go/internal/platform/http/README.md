# HTTP 基础契约

已有请求ID、模型路径/速率/并发中间件；这些能力需要显式组合，未放开诊断入口的业务API。

## 错误边界

`HandleErrors(Endpoint, logger)` 将处理器返回的显式错误变为Java兼容JSON信封。业务服务使用 `api.NewBusinessError(code, publicMessage)`，文案必须明确可公开；空文案保持为空，不自动替换。参数名/Violation字段来自代码定义，不能把请求值、SQL或原始异常消息填进去。

HTTP提供 `InvalidBody`、`MissingParameter`、`InvalidParameter`、`ValidationFailure`、`NotFound`、`MethodNotSupported`、`NotAcceptable`、`UploadTooLarge`、`IntegrityConflict`、`ClientDisconnected`。数据库完整性由仓储显式分类；边界不会从错误文本猜409。底层原因可 `errors.Is` 检查，但日志/响应不输出原因；最外层显式类型优先，多原因歧义失败关闭。

错误前置返回即使Accept为SSE也强制JSON，HEAD无body；405带Allow和无查询串路径/请求ID日志。日志不记录提交值、异常消息或panic堆栈。下载响应尚未提交时，错误会清除旧长度/压缩/附件头。

处理器必须在返回前完成ResponseWriter操作。1xx除101不算最终提交；写入/Flush/Hijack后返回error或panic时，用 `http.ErrAbortHandler` 中止连接，不能再写JSON污染SSE或文件。已建立流的业务错误仍应由业务层发送其协议事件。明确的客户端断连只记DEBUG、不再写入；普通context取消/上游超时不会自动当成断连。

适配器保留原有Flusher/Hijacker能力，支持ResponseController解包。未提供的可选接口不能因包装凭空出现。不得把该边界看作自动路由、请求解码、内容协商、multipart限制或认证链的替代。

## 真实Java对照

```bash
python3 scripts/migration/java_exception_fixture.py
```

从固定基线读取5个Java源文件，用实际BOM的离线依赖直接调用原处理器，核对36响应和405/断连日志；不启动Spring、不读.env或连接现有服务。默认仅核对，显式 `--write` 才更新快照。此证据只覆盖处理器，不覆盖Servlet分派、JSON绑定、验证提供器或完整业务安全链。
