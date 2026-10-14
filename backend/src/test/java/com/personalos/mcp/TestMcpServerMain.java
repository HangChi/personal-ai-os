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
 * <p><b>用法</b>：{@code java -cp <测试类路径> com.personalos.mcp.TestMcpServerMain <标记文件路径>}。
 * 客户端断开（stdin EOF）后主线程主动退出；关闭标记文件由 shutdown hook 写入——
 * 测试据此断言「server 进程真的退了」，而不是假设它礼貌。
 *
 * <p>工具清单刻意小而准：{@code echo}（回显，验参数）、{@code ping}（无参，验最简形态）。
 * 后续阶段的策略 / 审计 / 确认测试都基于这两个工具展开。
 */
public final class TestMcpServerMain {

    private TestMcpServerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path marker = Path.of(args[0]);
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
                .toolCall(McpSchema.Tool.builder()
                                .name("echo")
                                .description("回显输入文本")
                                .inputSchema(Map.of(
                                        "type", "object",
                                        "properties", Map.of("text", Map.of("type", "string")),
                                        "required", List.of("text")))
                                .build(),
                        (exchange, request) -> McpSchema.CallToolResult.builder()
                                .textContent(List.of("echo:" + request.arguments().get("text")))
                                .build())
                .toolCall(McpSchema.Tool.builder()
                                .name("ping")
                                .description("返回 pong")
                                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                                .build(),
                        (exchange, request) -> McpSchema.CallToolResult.builder()
                                .textContent(List.of("pong"))
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
