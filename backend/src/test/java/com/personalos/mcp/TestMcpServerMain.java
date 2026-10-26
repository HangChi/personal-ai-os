package com.personalos.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 测试专用 MCP server：以独立 JVM 子进程运行，经 stdio 讲真实 MCP 协议。
 *
 * <p><b>为什么用子进程而不是进程内管道</b>：客户端侧唯一可用的进程内传输是
 * {@code StdioClientTransport}——它自己派生进程。让测试 server 也作为子进程跑，
 * 客户端走的就是**生产同一条路径**（进程派生 + stdio 帧），fidelity 最高。
 * SDK 2.0.0 没有 in-memory 传输（spec research.md R-08 已实测）。
 *
 * <p><b>用法</b>：{@code java -cp <测试类路径> com.personalos.mcp.TestMcpServerMain
 * <标记文件路径> <日志文件路径>}。
 * 客户端断开（stdin EOF）后主线程主动退出；关闭标记文件由 shutdown hook 写入——
 * 测试据此断言「server 进程真的退了」，而不是假设它礼貌。
 *
 * <p>工具清单刻意覆盖三种形态：
 * <ul>
 *   <li>{@code echo}：回显输入（验参数传递，AUTO 策略用）；</li>
 *   <li>{@code ping}：无参最简形态（验 schema 自检不误伤）；</li>
 *   <li>{@code record}：把文本**追加写入日志文件**——有可观测的副作用，
 *       确认链路的断言落在真实效果上（CONFIRM 策略用：拦截时文件不出现，
 *       确认后才出现）。</li>
 * </ul>
 */
public final class TestMcpServerMain {

    private TestMcpServerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path marker = Path.of(args[0]);
        Path journal = Path.of(args[1]);
        CountDownLatch stdinClosed = new CountDownLatch(1);

        // stdio 协议的 stdout 只准承载 JSON-RPC 帧。
        // 测试 JVM 的类路径带着 logback（spring-boot-starter-logging 传递依赖），
        // 其默认配置把日志写到 System.out——那会污染协议流：客户端把日志行当 JSON 解析，
        // 撞见时间戳 "00:53:..." 报 "Invalid numeric value: Leading zeroes not allowed"。
        // 对策：在日志初始化前把 System.out 改道 stderr；协议输出用直通 fd 1 的独立流。
        PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
        System.setOut(new PrintStream(System.err, true));

        // 窥探 stdin EOF：传输层是唯一的读者，这里只放行 + 计数，不消费字节
        InputStream observedIn = new FilterInputStream(System.in) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b == -1) {
                    stdinClosed.countDown();
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n < 0) {
                    stdinClosed.countDown();
                }
                return n;
            }
        };

        McpSyncServer server = McpServer.sync(
                        new StdioServerTransportProvider(new JacksonMcpJsonMapperSupplier().get(),
                                observedIn, protocolOut))
                .serverInfo("test-mcp-server", "0.0.1")
                .toolCall(McpSchema.Tool.builder("echo", Map.of(
                                "type", "object",
                                "properties", Map.of("text", Map.of("type", "string")),
                                "required", List.of("text")))
                                .description("回显输入文本")
                                .build(),
                        (exchange, request) -> McpSchema.CallToolResult.builder()
                                .textContent(List.of("echo:" + request.arguments().get("text")))
                                .build())
                .toolCall(McpSchema.Tool.builder("ping", Map.of("type", "object", "properties", Map.of()))
                                .description("返回 pong")
                                .build(),
                        (exchange, request) -> McpSchema.CallToolResult.builder()
                                .textContent(List.of("pong"))
                                .build())
                .toolCall(McpSchema.Tool.builder("record", Map.of(
                                "type", "object",
                                "properties", Map.of("text", Map.of("type", "string")),
                                "required", List.of("text")))
                                .description("把文本追加写入日志文件（有副作用，用于验证确认链路）")
                                .build(),
                        (exchange, request) -> {
                            try {
                                Files.writeString(journal, "recorded:" + request.arguments().get("text") + "\n",
                                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            } catch (IOException ex) {
                                // 工具处理器不允许抛受检异常；转成运行时异常，
                                // SDK 会把它作为工具错误返回给客户端（也是真实 server 的常见形态）
                                throw new IllegalStateException("写入日志文件失败", ex);
                            }
                            return McpSchema.CallToolResult.builder()
                                    .textContent(List.of("recorded"))
                                    .build();
                        })
                .toolCall(McpSchema.Tool.builder("broken_schema",
                                Map.of("properties", Map.of("text", Map.of("type", "string"))))
                                .description("故意缺少 type:object 的坏 schema，用于验证装配期剔除")
                                .build(),
                        (exchange, request) -> McpSchema.CallToolResult.builder()
                                .textContent(List.of("never-exposed"))
                                .build())
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.writeString(marker, "closed");
            } catch (IOException ignored) {
                // 标记写失败只影响测试断言，不影响退出
            }
        }));

        // 阻塞到客户端断开；随后主动退出，不等 SDK 线程礼貌退场
        stdinClosed.await();
        server.closeGracefully();
        System.exit(0);
    }
}
