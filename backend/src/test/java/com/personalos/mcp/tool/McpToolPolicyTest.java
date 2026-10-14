package com.personalos.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link McpToolPolicy} 单元测试：默认从严（fail-closed）。
 */
class McpToolPolicyTest {

    @Test
    @DisplayName("命中 autoTools 清单 → AUTO")
    void listedToolIsAuto() {
        assertThat(McpToolPolicy.resolve("read_file", Set.of("read_file", "list_directory")))
                .isEqualTo(McpToolPolicy.AUTO);
    }

    @Test
    @DisplayName("未命中 → CONFIRM（默认从严：漏配的代价是多弹一次确认，而非未授权的写入）")
    void unlistedToolIsConfirm() {
        assertThat(McpToolPolicy.resolve("write_file", Set.of("read_file")))
                .isEqualTo(McpToolPolicy.CONFIRM);
    }

    @Test
    @DisplayName("清单为空或为 null → 全部 CONFIRM")
    void emptyOrNullListMeansAllConfirm() {
        assertThat(McpToolPolicy.resolve("read_file", Set.of())).isEqualTo(McpToolPolicy.CONFIRM);
        assertThat(McpToolPolicy.resolve("read_file", null)).isEqualTo(McpToolPolicy.CONFIRM);
    }

    @Test
    @DisplayName("按原始工具名匹配：清单写的是 server 声明的名字，不是加了前缀的装配名")
    void matchesOriginalNameNotAssembled() {
        // 配置里写 read_file 不该因为装配名是 mcp_filesystem_read_file 而匹配失败
        assertThat(McpToolPolicy.resolve("read_file", Set.of("read_file"))).isEqualTo(McpToolPolicy.AUTO);
    }
}
