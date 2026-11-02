package com.personalos.chat;

import static org.assertj.core.api.Assertions.*;
import com.personalos.agent.interaction.dto.AgentQuestion;
import com.personalos.agent.interaction.service.AgentInteractionService;
import com.personalos.chat.dto.ChatRunDto;
import com.personalos.chat.dto.StreamChatRequest;
import com.personalos.chat.repository.ChatRunRepository;
import com.personalos.chat.service.ChatRunService;
import com.personalos.chat.service.ChatSessionService;
import com.personalos.common.ai.ToolContributor;
import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.ai.tool.ToolExecutor;
import com.personalos.common.json.JsonCodec;
import com.personalos.support.ScriptedToolCallingChatModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties={"spring.ai.model.chat=none", "app.context.summary.min-messages=100000"})
@Testcontainers
class AgentInteractionIntegrationTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");
    @Autowired ChatRunService runs;
    @Autowired ChatRunRepository repository;
    @Autowired ChatSessionService sessions;
    @Autowired AgentInteractionService interactions;
    @Autowired ScriptedToolCallingChatModel model;
    @Autowired JsonCodec json;
    @Autowired com.personalos.common.confirmation.ConfirmationService confirmations;
    @Autowired SlowTools slow;
    static class SlowTools implements ToolContributor {
        final ToolExecutor executor;
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger effects = new AtomicInteger();
        SlowTools(ToolExecutor executor) { this.executor=executor; }
        @Override public List<Object> toolObjects() { return List.of(this); }
        @Tool(name="slowTestWrite", description="测试用的慢写操作")
        public String write(ToolContext context) {
            return executor.write("slowTestWrite", Map.of(), context, () -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("未收到放行"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                effects.incrementAndGet(); return ToolExecutionResult.ok("已写入", null);
            });
        }
    }
    @TestConfiguration static class Config {
        @Bean ScriptedToolCallingChatModel model() { return new ScriptedToolCallingChatModel(); }
        @Bean SlowTools slow(ToolExecutor executor) { return new SlowTools(executor); }
    }
    @BeforeEach void reset() { model.reset(); slow.effects.set(0); slow.entered=new CountDownLatch(1); slow.release=new CountDownLatch(1); }
    StreamChatRequest request(String text) { return new StreamChatRequest(text,false,4,"off",List.of()); }
    String key() { return UUID.randomUUID().toString(); }
    ChatRunDto await(String conversation, String id, String status) throws Exception {
        for (int i=0; i<400; i++) {
            if (repository.find(conversation,id).isEmpty()) { Thread.sleep(25); continue; }
            var result = runs.get(conversation,id,0);
            if (status.equals(result.status())) return result;
            Thread.sleep(25);
        }
        throw new AssertionError("未达到状态 " + status + ": " + runs.get(conversation,id,0));
    }
    @Test void questionPausesBeforeAnotherModelCallAndAnswerContinuesSameRun() throws Exception {
        model.enqueueToolCall("askUser", "{\"question\":\"报告按哪种方式组织？\",\"options\":[{\"label\":\"按日期\",\"description\":\"按时间排序\"},{\"label\":\"按项目\",\"description\":\"按项目归类\"}]}");
        model.enqueueText("已按你的回答整理");
        String c=sessions.create("澄清意图").conversationId();
        var first=runs.start(c,key(),request("整理报告"));
        var waiting=await(c,first.id(),"WAITING_USER");
        assertThat(waiting.question().options()).hasSize(2);
        assertThat(model.receivedInstructions()).hasSize(1);
        assertThat(waiting.events()).extracting(ChatRunDto.Event::name).contains("yield").doesNotContain("error");
        assertThatThrownBy(() -> runs.resume(c,first.id())).hasMessageContaining("先回答");
        runs.answer(c, first.id(), waiting.question().id(), "按项目，保留风险说明");
        assertThat(await(c,first.id(),"COMPLETED").question()).isNull();
        assertThat(model.receivedInstructions()).hasSize(2);
        assertThat(model.receivedInstructions().getLast().toString()).contains("按项目，保留风险说明");
        runs.answer(c,first.id(),waiting.question().id(),"按项目，保留风险说明");
        assertThat(model.receivedInstructions()).hasSize(2);
        assertThatThrownBy(() -> runs.answer(c,first.id(),waiting.question().id(),"覆盖答案")).hasMessageContaining("已经回答");
    }
    @Test void queuedMessagesExecuteInOrderAndDuplicateSubmissionIsNotDuplicated() throws Exception {
        model.delayStreamBy(Duration.ofMillis(400));
        model.enqueueText("第一轮完成"); model.enqueueText("第二轮完成"); model.enqueueText("第三轮完成");
        String c=sessions.create("排队处理").conversationId();
        var first=runs.start(c,key(),request("第一条"));
        String second=key(),third=key();
        runs.followup(c,first.id(),second,"QUEUE",request("第二条"));
        runs.followup(c,first.id(),second,"QUEUE",request("第二条"));
        assertThat(runs.followup(c,first.id(),third,"QUEUE",request("第三条")).followups()).hasSize(2);
        await(c,third,"COMPLETED");
        assertThat(runs.get(c,second,0).status()).isEqualTo("COMPLETED");
        assertThat(model.receivedInstructions()).hasSize(3);
        assertThat(model.receivedInstructions().get(1).toString()).contains("第二条");
        assertThat(model.receivedInstructions().get(2).toString()).contains("第三条");
    }
    @Test void steeringWaitsForInFlightToolAndThenAppliesWithoutRepeatingItsEffect() throws Exception {
        model.enqueueToolCall("slowTestWrite","{}"); model.enqueueText("按补充要求给出表格");
        String c=sessions.create("引导当前任务").conversationId();
        var run=runs.start(c,key(),request("整理任务"));
        assertThat(slow.entered.await(5,TimeUnit.SECONDS)).isTrue();
        runs.followup(c,run.id(),key(),"STEER",request("请改用表格输出，只保留高优先级"));
        assertThat(slow.effects).hasValue(0);
        slow.release.countDown();
        var done=await(c,run.id(),"COMPLETED");
        assertThat(slow.effects).hasValue(1);
        assertThat(done.events()).extracting(ChatRunDto.Event::name).contains("yield","reset").doesNotContain("error");
        assertThat(model.receivedInstructions().getLast().toString()).contains("请改用表格输出，只保留高优先级");
    }
    @Test void queueCanBeRemovedAndCannotBypassWaitingQuestion() throws Exception {
        String c=sessions.create("等待回答时排队").conversationId();
        String id=key(); repository.create(id,c,json.toJson(request("原任务")));
        var q=interactions.ask(c,id,"你希望采用哪个时间范围？",List.of());
        runs.recover();
        assertThat(runs.get(c,id,0).status()).isEqualTo("WAITING_USER");
        String queued=key(); runs.followup(c,id,queued,"QUEUE",request("随后生成摘要"));
        assertThat(model.receivedInstructions()).isEmpty();
        runs.cancelFollowup(c,id,queued);
        assertThat(runs.get(c,id,0).followups()).isEmpty();
        String other=sessions.create("另一会话").conversationId();
        assertThatThrownBy(() -> runs.answer(other,id,q.id(),"本周")).hasMessageContaining("不存在");
    }
    @Test void queuedMessageCanBeEditedWithoutChangingPositionOrAttachments() {
        String c=sessions.create("编辑排队消息").conversationId(); String id=key();
        repository.create(id,c,json.toJson(request("原任务")));
        String first=key(), second=key();
        var original=new StreamChatRequest("原始要求",true,8,"deep",List.of(new com.personalos.chat.dto.ChatAttachmentDto("fixture","参考.txt","text/plain",12)));
        runs.followup(c,id,first,"QUEUE",original);
        runs.followup(c,id,second,"QUEUE",request("第二条"));
        var edited=runs.editFollowup(c,id,first,"  修改后的要求  ");
        assertThat(edited.followups()).extracting(com.personalos.agent.interaction.dto.AgentFollowup::id).containsExactly(first,second);
        var saved=edited.followups().getFirst().request();
        assertThat(saved.content()).isEqualTo("修改后的要求");
        assertThat(saved.attachments()).isEqualTo(original.attachments());
        assertThat(saved.useKnowledge()).isTrue(); assertThat(saved.topK()).isEqualTo(8); assertThat(saved.thinkingLevel()).isEqualTo("deep");
        assertThatThrownBy(() -> runs.editFollowup(c,id,first," ")).hasMessageContaining("消息须");
        runs.cancelFollowup(c,id,first);
        assertThat(runs.get(c,id,0).followups()).extracting(com.personalos.agent.interaction.dto.AgentFollowup::id).containsExactly(second);
        assertThatThrownBy(() -> runs.editFollowup(c,id,first,"不能再改")).hasMessageContaining("无法编辑");
        assertThatThrownBy(() -> runs.steerFollowup(c,id,first)).hasMessageContaining("无法改为引导");
        String other=sessions.create("其他会话").conversationId(); String otherId=key();
        repository.create(otherId,other,json.toJson(request("另一个任务")));
        assertThatThrownBy(() -> runs.editFollowup(other,otherId,second,"跨会话修改")).hasMessageContaining("不存在");
        repository.transition(id,"RUNNING","STOPPED"); repository.transition(otherId,"RUNNING","STOPPED");
    }
    @Test void queuedMessageSteersCurrentRunOnceAndIsNeverExecutedAgainAsQueuedTask() throws Exception {
        String c=sessions.create("排队转为引导").conversationId(); String old=key();
        repository.create(old,c,json.toJson(request("较早的任务")));
        String queued=key(); runs.followup(c,old,queued,"QUEUE",request("改用表格，只保留风险"));
        repository.transition(old,"RUNNING","STOPPED");
        model.enqueueToolCall("slowTestWrite","{}"); model.enqueueText("已按风险表格整理");
        var current=runs.start(c,key(),request("当前对话任务"));
        assertThat(slow.entered.await(5,TimeUnit.SECONDS)).isTrue();
        var steered=runs.steerFollowup(c,current.id(),queued);
        assertThat(steered.followups()).hasSize(1);
        assertThat(steered.followups().getFirst().runId()).isEqualTo(current.id());
        assertThat(steered.followups().getFirst().mode()).isEqualTo("STEER");
        assertThat(slow.effects).hasValue(0);
        slow.release.countDown();
        await(c,current.id(),"COMPLETED");
        assertThat(slow.effects).hasValue(1);
        assertThat(model.receivedInstructions().getLast().toString()).contains("改用表格，只保留风险");
        assertThat(repository.find(c,queued)).isEmpty();
        int calls=model.receivedInstructions().size();
        assertThat(runs.steerFollowup(c,current.id(),queued).status()).isEqualTo("COMPLETED");
        assertThat(model.receivedInstructions()).hasSize(calls);
    }
    @Test void restartRecoversAnAnswerCommittedBeforeResumeWasStarted() throws Exception {
        String c=sessions.create("答案已保存的崩溃窗口").conversationId(); String id=key();
        repository.create(id,c,json.toJson(request("原任务")));
        var question=interactions.ask(c,id,"采用哪种格式？",List.of());
        repository.transition(id,"RUNNING","WAITING_USER");
        interactions.answer(id,question.id(),"表格，附风险说明");
        model.enqueueText("已依据保存的答案继续");
        runs.recover();
        assertThat(await(c,id,"COMPLETED").question()).isNull();
        assertThat(model.receivedInstructions().getLast().toString()).contains("表格，附风险说明");
    }
    @Test void answeringAQuestionDoesNotAuthorizeAnUnrelatedDangerousOperation() {
        String c=sessions.create("澄清不是授权").conversationId(); String id=key();
        repository.create(id,c,json.toJson(request("原任务")));
        var q=interactions.ask(c,id,"你希望处理哪个项目？",List.of());
        var approval=confirmations.create(com.personalos.common.confirmation.ConfirmationAction.DELETE_TASK,
                "删除测试任务",Map.of(),1,c);
        runs.recover();
        assertThat(runs.answer(c,id,q.id(),"项目A").status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(model.receivedInstructions()).isEmpty();
        assertThat(confirmations.list(c,com.personalos.common.confirmation.ConfirmationStatus.PENDING))
                .extracting(com.personalos.common.confirmation.ConfirmationDto::id).contains(approval.id());
    }
    @Test void steeringDuringTextGenerationReplacesRemainingOutputWithNewIntent() throws Exception {
        model.delayStreamBy(Duration.ofMillis(300));
        model.enqueueText("旧方向的回答"); model.enqueueText("根据新要求生成的回答");
        String c=sessions.create("生成中引导").conversationId();
        var run=runs.start(c,key(),request("给出详细报告"));
        for (int i=0; i<100 && model.receivedInstructions().isEmpty(); i++) Thread.sleep(5);
        assertThat(model.receivedInstructions()).hasSize(1);
        runs.followup(c,run.id(),key(),"STEER",request("只给三条结论"));
        var done=await(c,run.id(),"COMPLETED");
        assertThat(done.events().stream().filter(event->event.name().equals("delta")).map(ChatRunDto.Event::payload).toList())
                .allMatch(payload -> !payload.contains("旧方向的回答"));
        assertThat(model.receivedInstructions().getLast().toString()).contains("只给三条结论");
    }
    @Test void answeredQuestionIsNotAskedAgainAndInvalidOptionsAreRejected() {
        String c=sessions.create("重复问题").conversationId(); String id=key(); repository.create(id,c,json.toJson(request("原任务")));
        var q=interactions.ask(c,id,"选哪个？",List.of(new AgentQuestion.Option("A","方案A")));
        interactions.answer(id,q.id(),"A");
        assertThat(interactions.ask(c,id,"选哪个？",List.of(new AgentQuestion.Option("A","方案A"))).status()).isEqualTo("ANSWERED");
        assertThatThrownBy(() -> interactions.ask(c,id,"问题",List.of(new AgentQuestion.Option("",null)))).hasMessageContaining("选项");
        repository.transition(id,"RUNNING","COMPLETED");
    }
}
