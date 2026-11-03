# 显式配置基础

默认入口只接受隔离诊断配置：回环8081，不读.env，不连接基础设施，生产/认证/CSRF模式在监听前拒绝。`.env`读取必须由系统环境显式开启，优先级与生产校验已有独立测试；任意YAML/profile/全部属性绑定仍待迁移。

## Cookie真实配置来源

原Java的SecurityConfig、SharedSessionConfig和ProductionSecurityValidator读取`server.servlet.session.cookie.secure`，对应环境变量`SERVER_SERVLET_SESSION_COOKIE_SECURE`。原base/prod YAML却将`SESSION_COOKIE_SECURE`占位符/静态true放在`spring.servlet.session.cookie.secure`。这两个路径不同，不能把后者误认为已开启安全Cookie。

Go保留实际canonical路径及SystemEnvironment的点/下划线/大写别名，先查全部系统环境别名，再查低优先级.env；canonical缺席时保持false，即使prod或SESSION_COOKIE_SECURE=true也不伪造true。真实Spring来源与原生产守卫的4场景见`specs/033-go-rewrite/config-inventory.json`。原Java/YAML未改；Go生产、认证与CSRF启动守卫仍生效。此前参数级生产校验测试不代表完整来源绑定等价。

`python3 scripts/migration/go_config_inventory.py`可离线复验全部配置record字段/默认注解、@Value、直接getProperty及逐文档YAML来源。它不执行完整Binder、profile激活或动态MCP装配。

## 时长与数据大小

`ParseDuration(string)` 返回 `*time.Duration,error`，`ParseDataSize(string)` 返回 `*int64,error`。空源字符串返回nil、nil，与Java转换器null一致；零值、负值和null不同，由使用方执行正值等业务校验。每次成功返回独立指针，错误不带配置原文。构造本身不读环境或文件、不连接服务。

时长对齐未注解Spring转换：裸整数按毫秒，整数后缀ns/us/ms/s/m/h/d可用ASCII大小写，或Java ISO-8601日/时/分/秒。支持正负分量、点/逗号与最多9位秒小数；不接受Go的1.5s、1h30m、微秒Unicode单位，也不额外trim空白。ISO按Java中间long乘法/加法顺序检查。

大小默认byte，B/KB/MB/GB/TB为1024倍率，后缀大小写敏感；移除全部Java空白，包括中间空白，保留NBSP/NEL等。8MB是8,388,608字节。两种转换都不截断或饱和；Java Duration比Go范围大，无法fit int64纳秒的值以 `errors.Is(err, ErrScalarOverflow)` 明确拒绝，这是记录在research的迁移范围收紧。

```bash
python3 scripts/migration/java_config_scalar_fixture.py
```

该命令从固定基线BOM解析本机离线依赖，直接调用真实Boot ApplicationConversionService核对228项。默认只比较，显式--write才更新快照；不启动Spring或读取.env。它不证明完整Binder、带DurationUnit/DataSizeUnit注解、YAML或配置来源优先级兼容。
