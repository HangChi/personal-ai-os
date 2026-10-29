package com.personalos.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personalos.chat.entity.ChatMessageEntity;
import com.personalos.chat.repository.ChatMessageRepository;
import com.personalos.common.ai.AiProperties;
import com.personalos.common.ai.ContextBudgetService;
import com.personalos.common.ai.PromptContributor;
import com.personalos.common.ai.ToolSchemaOverhead;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.support.StaticObjectProvider;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatPromptAssembler} 单元测试。
 *
 * <p>系统提示是**格式契约**（见 contracts C-01）：顺序、分隔空行、知识库编号一旦变动，
 * 模型行为与引用编号都会跟着变，而且不会报错。所以这里逐字符断言，而不是只测「包含某段文字」。
 */
class ChatPromptAssemblerTest {

    private static final String BASE_PROMPT = "你是助手。";
    private static final String CONVERSATION = "c-1";

    private ChatMessageRepository messageRepository;
    private ChatSummaryService summaryService;
    private ToolSchemaOverhead toolSchemaOverhead;
    private ContextBudgetService contextBudget;

    /** 工具定义的固定字符数：取值随意，但必须非 0 —— 用 0 会让「有没有算进去」测不出来 */
    private static final int TOOL_CHARS = 4000;

    @BeforeEach
    void setUp() {
        messageRepository = mock(ChatMessageRepository.class);
        summaryService = mock(ChatSummaryService.class);
        toolSchemaOverhead = mock(ToolSchemaOverhead.class);
        contextBudget = mock(ContextBudgetService.class);
        when(toolSchemaOverhead.chars()).thenReturn(TOOL_CHARS);
        // 默认：无历史、无摘要 —— 各用例按需覆盖
        when(messageRepository.findRecentByConversationId(anyString(), any())).thenReturn(List.of());
        when(summaryService.renderForConversation(anyString())).thenReturn("");
    }

    private ChatPromptAssembler assembler(PromptContributor... contributors) {
        AiProperties properties = new AiProperties(new AiProperties.Memory(20, 32_000), BASE_PROMPT);
        return new ChatPromptAssembler(properties, new StaticObjectProvider<>(List.of(contributors)),
                summaryService, messageRepository, toolSchemaOverhead, contextBudget);
    }

    @Test
    @DisplayName("没有任何上下文时，系统提示与人设逐字符相同（不凭空多出空行）")
    void basePromptOnly() {
        assertThat(assembler().assemble(List.of(), "你好", CONVERSATION)).isEqualTo(BASE_PROMPT);
    }

    @Test
    @DisplayName("摘要以空行追加在人设之后")
    void appendsSummary() {
        when(summaryService.renderForConversation(CONVERSATION)).thenReturn("早前聊过排课。");

        assertThat(assembler().assemble(List.of(), "继续", CONVERSATION))
                .isEqualTo(BASE_PROMPT + "\n\n早前聊过排课。");
    }

    /** 固定返回一段文本的贡献者（实现无参版本即可，带参版本默认委托给它） */
    private static PromptContributor contributor(String text) {
        return new PromptContributor() {
            @Override
            public String contribute() {
                return text;
            }
        };
    }

    @Test
    @DisplayName("多个贡献者按注册顺序追加，返回空串的被跳过（不留连续空行）")
    void appendsContributorsInOrderAndSkipsBlank() {
        PromptContributor first = contributor("记忆块");
        PromptContributor silent = contributor("   "); // 约定：无内容返回空串，空白也应跳过
        PromptContributor last = contributor("日程块");

        String prompt = assembler(first, silent, last).assemble(List.of(), "今天有什么", CONVERSATION);

        assertThat(prompt).isEqualTo(BASE_PROMPT + "\n\n记忆块" + "\n\n日程块");
    }

    @Test
    @DisplayName("贡献者拿到的是本轮用户输入（用于相关性筛选）")
    void passesUserInputToContributors() {
        StringBuilder seen = new StringBuilder();
        PromptContributor spy = new PromptContributor() {
            @Override
            public String contribute() {
                return "";
            }

            @Override
            public String contribute(String userInput) {
                seen.append(userInput);
                return "命中";
            }
        };

        assembler(spy).assemble(List.of(), "明天的课", CONVERSATION);

        assertThat(seen.toString()).isEqualTo("明天的课");
    }

    @Test
    @DisplayName("知识库区块：编号从 1 开始，带标题/文件名/段号/原文，并附使用约束")
    void appendsKnowledgeSection() {
        KnowledgeHitDto first = new KnowledgeHitDto(
                UUID.randomUUID(), "排课说明", "schedule.md", 0, "第一节内容", 0.7);
        KnowledgeHitDto second = new KnowledgeHitDto(
                UUID.randomUUID(), "校历", "calendar.md", 2, "第二节内容", null);

        String prompt = assembler().assemble(List.of(first, second), "怎么排课", CONVERSATION);

        assertThat(prompt).startsWith(BASE_PROMPT);
        assertThat(prompt)
                .contains("以下内容来自用户的个人知识库检索结果")
                .contains("[1] 文档《排课说明》（schedule.md，第 1 段）\n第一节内容")
                .contains("[2] 文档《校历》（calendar.md，第 3 段）\n第二节内容")
                .contains("回答必须基于上述内容");
    }

    @Test
    @DisplayName("无命中时不出现知识库区块（否则模型会以为「检索到了空内容」）")
    void omitsKnowledgeSectionWhenNoHits() {
        String prompt = assembler().assemble(List.of(), "随便问问", CONVERSATION);

        assertThat(prompt).doesNotContain("知识库检索结果");
    }

    @Test
    @DisplayName("标题或文件名为空时用「未命名」占位，而不是留一个空括号")
    void replacesBlankTitles() {
        KnowledgeHitDto hit = new KnowledgeHitDto(UUID.randomUUID(), null, " ", 0, "内容", 0.5);

        String prompt = assembler().assemble(List.of(hit), "问题", CONVERSATION);

        assertThat(prompt).contains("《未命名》（未命名，第 1 段）");
    }

    @Test
    @DisplayName("历史字符估算：累加正文长度，空正文按 0 计")
    void estimatesHistoryChars() {
        ChatMessageEntity withContent = mock(ChatMessageEntity.class);
        when(withContent.getContent()).thenReturn("12345");
        ChatMessageEntity withoutContent = mock(ChatMessageEntity.class);
        when(withoutContent.getContent()).thenReturn(null);
        when(messageRepository.findRecentByConversationId(anyString(), any()))
                .thenReturn(List.of(withContent, withoutContent));

        assertThat(assembler().estimateHistoryChars(CONVERSATION)).isEqualTo(5);
    }

    @Test
    @DisplayName("estimatePromptChars = 系统提示 + 历史 + 工具定义（仪表盘口径）")
    void estimatesPromptCharsWithoutKnowledge() {
        ChatMessageEntity message = mock(ChatMessageEntity.class);
        when(message.getContent()).thenReturn("1234567890");
        when(messageRepository.findRecentByConversationId(anyString(), any())).thenReturn(List.of(message));
        when(summaryService.renderForConversation(CONVERSATION)).thenReturn("摘要");

        // 人设 5 字 + "\n\n" 2 字 + 摘要 2 字 = 9；历史 10；工具定义 TOOL_CHARS
        // 工具必须计入：漏掉它会让仪表盘比真实值小一个数量级（103 vs 4116）
        assertThat(assembler().estimatePromptChars(CONVERSATION)).isEqualTo(19 + TOOL_CHARS);
    }
}
