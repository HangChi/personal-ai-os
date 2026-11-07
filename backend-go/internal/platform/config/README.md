# 显式配置基础

> 本页记录模块契约及分阶段验证范围。当前启动/模块组合见[Go 文档中心](../../../docs/README.md)，两版最新能力与差异见[双端说明](../../../../docs/backend-parity.md)；下文固定基线、测试数和 P1/P3 等范围不是当前全产品或生产批准。

默认入口只接受隔离诊断配置：回环8081，不读.env，不连接基础设施，生产/认证/CSRF模式在监听前拒绝。`.env`读取必须由系统环境显式开启，优先级与生产校验已有独立测试；显式Foundation使用下述固定基线Properties；任意外部YAML位置/import和profile分组明确拒绝。

## Cookie真实配置来源

原Java的SecurityConfig、SharedSessionConfig和ProductionSecurityValidator读取`server.servlet.session.cookie.secure`，对应环境变量`SERVER_SERVLET_SESSION_COOKIE_SECURE`。原base/prod YAML却将`SESSION_COOKIE_SECURE`占位符/静态true放在`spring.servlet.session.cookie.secure`。这两个路径不同，不能把后者误认为已开启安全Cookie。

Go保留实际canonical路径及SystemEnvironment的点/下划线/大写别名，先查全部系统环境别名，再查低优先级.env；canonical缺席时保持false，即使prod或SESSION_COOKIE_SECURE=true也不伪造true。真实Spring来源与原生产守卫的4场景见`specs/033-go-rewrite/config-inventory.json`。原Java/YAML未改；诊断模式仍拒绝认证/CSRF开关，Foundation允许已实现安全链，所有生产模式仍拒绝。此前参数级生产校验测试不代表完整来源绑定等价。

`python3 scripts/migration/go_config_inventory.py`可离线复验全部配置record字段/默认注解、@Value、直接getProperty及逐文档YAML来源。它不执行完整Binder、profile激活或动态MCP装配。

## 时长与数据大小

`ParseDuration(string)` 返回 `*time.Duration,error`，`ParseDataSize(string)` 返回 `*int64,error`。空源字符串返回nil、nil，与Java转换器null一致；零值、负值和null不同，由使用方执行正值等业务校验。每次成功返回独立指针，错误不带配置原文。构造本身不读环境或文件、不连接服务。

时长对齐未注解Spring转换：裸整数按毫秒，整数后缀ns/us/ms/s/m/h/d可用ASCII大小写，或Java ISO-8601日/时/分/秒。支持正负分量、点/逗号与最多9位秒小数；不接受Go的1.5s、1h30m、微秒Unicode单位，也不额外trim空白。ISO按Java中间long乘法/加法顺序检查。

大小默认byte，B/KB/MB/GB/TB为1024倍率，后缀大小写敏感；移除全部Java空白，包括中间空白，保留NBSP/NEL等。8MB是8,388,608字节。两种转换都不截断或饱和；Java Duration比Go范围大，无法fit int64纳秒的值以 `errors.Is(err, ErrScalarOverflow)` 明确拒绝，这是记录在research的迁移范围收紧。

```bash
python3 scripts/migration/java_config_scalar_fixture.py
```

该命令从固定基线BOM解析本机离线依赖，直接调用真实Boot ApplicationConversionService核对228项。默认只比较，显式--write才更新快照；不启动Spring或读取.env。它不证明完整Binder、带DurationUnit/DataSizeUnit注解、YAML或配置来源优先级兼容。

## 类型化配置绑定

`BindAuthProperties`、`BindRateLimitProperties`、`BindAgentExecutionProperties`只接收已解析的canonical字符串map，例如`app.auth.max-failures`。nil/空map采用原record的DefaultValue和紧凑构造器；这里没有自动叠加application.yml，不读取宿主环境/.env，不实现relaxed键转换、YAML/profile或占位符。调用方必须先完成来源优先级与canonical化，不能把原始环境map直接交进来。未知字段与该默认Binder一样忽略。

真实Spring Binder.bindOrCreate的241项对照区分缺省/空/零/非法：缺省整数采用注解，显式空整数报错；空时长转换成null后采用注解，纯空白报错；关闭限流仍校验容量/周期。整数遵循Java 32位、内部Java空白移除、hex与BMP Unicode数字语法，补充平面数学数字不被当作UTF-16 char接受。4个Java合法但超Go时长范围的输入保持显式ErrScalarOverflow，不截断。

认证绑定保留密码nil/空值且不trim，用户名使用Java strip/default。用户名/密码不参与JSON或格式化；`Password()`返回独立副本，只供显式凭据编码器使用。record本身允许认证开启而密码缺席，与原Java相同；AuthService随后负责必填/BCrypt校验，不能绕过。测试已将绑定结果交给真实Go认证和限流组件验证，主诊断应用仍不装配这些能力。

```bash
python3 scripts/migration/java_config_binding_fixture.py
```

夹具从固定Git源码独立编译三类record并使用真实Binder，只注入合成字符串map、PlaceholdersResolver.NONE，不读取系统属性、真实环境、文件配置或启动应用。默认重生成比较，--write显式更新。Agent这里只覆盖配置绑定，不表示P4执行引擎已迁移。

## MCP动态配置来源

`BindMCPProperties(sources ...BindingSource)`在参数内显式组合来源，顺序由高到低。普通来源支持`mcp.servers.fetch.command`、camel/kebab字段名、`args[0]`连续索引和方括号map键；`Environment:true`来源使用SystemEnvironment的下划线/索引映射。`DotEnv.BindingSource()`仅复制已经解析的字面值，不访问文件。调用方必须显式枚举需要使用的系统变量；这里不会从进程环境补全。

Map按键合并，单server各字段独立覆盖；List/Set取最高优先级来源的整个集合，不能拼接低优先级剩余项。空列表仍会阻止回落，索引缺口/非规范索引拒绝。逗号列表使用Java String.trim的ASCII控制字符规则，不执行shell/CSV引号或反斜线转义。Set只去重、不承诺顺序；访问器为稳定输出排序。

原SystemEnvironment有一处需显式注意：`MCP_SERVERS_FETCH_ENV_API_KEY`实际绑定为`env["api.key"]`，不是`API_KEY`；`HTTP_PROXY`同样变成`http.proxy`。普通属性的`mcp.servers.fetch.env.[API_KEY]`才保留该原样键。Go按真实基线结果保留此行为，不自行修复；以后调整应作为有明确迁移说明的行为变更。单数MCP_SERVER不会声明server，带额外下划线的server名字也不能臆测为完整名字。

MCP配置和server字段全部私有，命令指针、server名称、参数、环境map和工具集合访问均返回副本；格式化/JSON只输出脱敏信息。command的null/空串保持区别，连接期限为null/非正值时归一到10秒。先按Java Duration完整范围检查合法性再归一化，合法巨大负值仍归一化，Java自身溢出则拒绝；归一化后仍超Go范围的正值明确ErrScalarOverflow。

离线Binder夹具已扩展为四类record共398项，其中MCP157项；只使用显式合成MAP/正确命名为*-systemEnvironment的来源。当前实现针对上述MCP键形态，不是任意Spring Binder替代；不支持ConfigData/profile激活、占位符/SpEL、任意配置类型、宿主来源发现或应用接线。同一显式map以排序键枚举；任意别名冲突和其余relaxed绑定边界仍需继续验收。没有启动MCP子进程或连接协议服务，P7保持未开始。

## 全部12类record

后续扩展的`module_binding.go`提供其余8类`Bind*Properties`，真实Binder夹具累计1475项，覆盖全部12类配置record。输入仍为已解析canonical字符串map，不加载YAML、环境或业务运行时。`AppProperties`字符串保留null/空，提示文本和应用标识常规格式化/JSON脱敏，集合访问返回副本。

特别保留原构造器行为：Calendar的首次扫描延迟缺省为0，Knowledge相似阈值缺省为0，Protection绑定缺省重试0而显式defaults工厂为1；Context.summary与Memory.ranking/backfill完全缺省时enabled=true，绑定其余已知子字段后primitive缺省false。仅空时长转换null不构成nested绑定，未知字段也不会创建nested。

## 原YAML的配置来源解析

`LoadBaselineProperties(PropertyOptions{Overrides: ..., System: ..., DotEnv: ...})`只使用显式map与`baseline.json`嵌入基线，不搜索文件、读取宿主环境或创建连接。基线由真实Spring YAML加载器生成，保留7文件/17文档和原占位符；`java_config_data_fixture.py`从固定Git源码核对原文哈希、执行真正Boot ConfigData并复验生成内容。

来源顺序是显式canonical覆盖→系统环境→按激活顺序反向排列的profile文件→反向多文档基础配置→dotenv。系统变量和dotenv仍分别保留SystemEnvironment别名。`Get`区分缺省/显式空、延迟解析嵌套占位符/默认值/转义，循环或无法解析时脱敏拒绝；有64层与1MiB展开上限。各map在构造时复制，profiles访问复制，所有读取可并发且无可变缓存。

原DotEnv处理器没有Ordered/Order，晚于有显式ORDER的ConfigData执行。因此dotenv中的SPRING_PROFILES_ACTIVE可能被Environment延迟报告为active，却不会重新加载对应profile文件；不能据此擅自套用prod或worker文件。133个真实场景/189属性覆盖该区别、系统/default/include激活、文档/profile顺序和别名优先级。原`Environment.Config()`诊断配置子集尚未切换到本解析器，其历史profile测试不代表完整Boot启动行为。

仅支持固定基线已有文档结构；外部`spring.config.*`位置/导入和profile分组明确拒绝，不冒充任意Spring ConfigData替代。`Properties.Foundation()`组合P1使用的角色、Auth/RateLimit/App、安全开关、模型并发与专用Redis条件/namespace；不打开连接，生产配置继续拒绝。Redis条件沿用原ConditionalOnProperty的精确true（忽略大小写），不把on/1当作开启。主入口已接P1配置与受限数据源/管理/会话/上传/日志装配；其余业务record有独立Binder对照，实际使用方按对应阶段迁移。复验：`python3 scripts/migration/java_config_data_fixture.py`，默认比较，显式`--write`更新内置基线与测试快照。

整数按原32/64位语法检查再归一；浮点沿用Java Double（包含hex、f/d后缀、NaN/Infinity和IEEE溢出）的实际结果，不能静默替换NaN或把合法溢出归一化当作非法。时长先在Java完整范围解析，合法非正值按各record默认处理，归一化后超Go范围仍明确拒绝。知识库扩展名规则与查询限额、Calendar有效前瞻/范围/限额等配置辅助方法已实现；相应业务执行仍属于后续阶段。
