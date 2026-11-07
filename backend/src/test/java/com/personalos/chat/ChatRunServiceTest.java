package com.personalos.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.agent.interaction.dto.*;
import com.personalos.agent.interaction.service.AgentInteractionService;
import com.personalos.chat.dto.*;
import com.personalos.chat.repository.ChatRunRepository;
import com.personalos.chat.repository.ChatRunRepository.Run;
import com.personalos.chat.service.*;
import com.personalos.common.config.RuntimeRole;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import com.personalos.common.web.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/** 验证任务恢复、配额、队列与事件终态，不调用真实模型或数据库。 */
class ChatRunServiceTest {
    final ChatRunRepository repo = mock(ChatRunRepository.class);
    final ChatCompletionService completion = mock(ChatCompletionService.class);
    final ChatSessionService sessions = mock(ChatSessionService.class);
    final AgentInteractionService interactions = mock(AgentInteractionService.class);
    final ModelConcurrencyGate gate = new ModelConcurrencyGate(1);
    final TokenBucketRateLimiter limiter = mock(TokenBucketRateLimiter.class);
    final RunEventHub hub = mock(RunEventHub.class);
    final RunEventDelivery delivery = new RunEventDelivery(hub);
    final JsonCodec json = new JsonCodec(new ObjectMapper());
    final StreamChatRequest request = new StreamChatRequest("整理任务", true, 3, "off", List.of());
    final ChatRunService service = new ChatRunService(repo, completion, sessions, json, interactions, gate, limiter, hub, delivery);
    RunEmitter emitter;
    @BeforeEach void setup() {
        when(limiter.tryAcquire()).thenReturn(true);
        doAnswer(call -> {
            String id = call.getArgument(0), conversation = call.getArgument(1), payload = call.getArgument(2);
            when(repo.find(conversation, id)).thenReturn(Optional.of(new Run(id, conversation, payload, "RUNNING")));
            return null;
        }).when(repo).create(anyString(), anyString(), anyString());
        doAnswer(call -> { emitter = call.getArgument(7); return null; }).when(completion)
                .stream(anyString(), anyString(), anyString(), anyBoolean(), anyInt(), any(), anyList(), any());
    }
    @AfterEach void close() {
        if (emitter != null) emitter.complete();
        ReflectionTestUtils.invokeMethod(delivery, "close");
    }
    Run run(String status) { return new Run("r", "c", json.toJson(request), status); }
    void stored(String status) { when(repo.find("c", "r")).thenReturn(Optional.of(run(status))); }
    void resumeAllowed(String status) { stored(status); when(repo.beginResume("r", status, false)).thenReturn(true); }
    AgentQuestion question() { return new AgentQuestion("q", "r", "选格式", List.of(), "PENDING", null); }
    AgentFollowup input(String id, String mode) { return new AgentFollowup(id, "r", "c", mode, request, "PENDING"); }
    void released() { var lease = gate.tryAcquire(); assertThat(lease).isNotNull(); lease.close(); }

    @Test void startDeduplicatesAndRejectsConflictingKeys() {
        stored("COMPLETED");
        assertThat(service.start("c", "r", request).status()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> service.start("c", "r", new StreamChatRequest("其他", false, null, null, null))).hasMessageContaining("其他消息");
        assertThatThrownBy(() -> service.start("c", "x".repeat(65), request)).hasMessageContaining("过长");
        verifyNoInteractions(completion, limiter);
    }
    @Test void startBlocksActiveTasksAndQuestions() {
        when(repo.runningFor("c")).thenReturn(true);
        assertThatThrownBy(() -> service.start("c", "r", request)).hasMessageContaining("正在执行");
        when(repo.runningFor("c")).thenReturn(false);
        when(repo.latest("c")).thenReturn(Optional.of(run("WAITING_USER")));
        when(interactions.pendingQuestion("r")).thenReturn(question());
        assertThatThrownBy(() -> service.start("c", "r", request)).hasMessageContaining("先回答");
        verify(repo, never()).create(anyString(), anyString(), anyString());
    }
    @Test void quotaAndPersistenceFailuresReleaseLeases() {
        stored("STOPPED"); when(limiter.tryAcquire()).thenReturn(false);
        assertThatThrownBy(() -> service.resume("c", "r")).isInstanceOf(BusinessException.class); released();
        when(limiter.tryAcquire()).thenReturn(true);
        when(repo.beginResume("r", "STOPPED", false)).thenThrow(new IllegalStateException("synthetic failure"));
        assertThatThrownBy(() -> service.resume("c", "r")).hasMessageContaining("synthetic"); released();
        var held = gate.tryAcquire();
        assertThatThrownBy(() -> service.resume("c", "r")).isInstanceOf(BusinessException.class); held.close();
        doThrow(new IllegalStateException("synthetic create failure")).when(repo).create(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> service.start("c", "new", request)).hasMessageContaining("synthetic"); released();
    }
    @ParameterizedTest @ValueSource(strings = {"RUNNING", "COMPLETED", "STOPPED"})
    void resumeHonorsStatusAndLostCompareAndSet(String status) {
        stored(status);
        if (status.equals("COMPLETED")) assertThatThrownBy(() -> service.resume("c", "r")).hasMessageContaining("已完成");
        else assertThat(service.resume("c", "r").id()).isEqualTo("r");
        verifyNoInteractions(completion); released();
    }
    @ParameterizedTest @ValueSource(strings = {"running", "question", "pending", "uncertain"})
    void resumeGuardsUnresolvedWork(String guard) {
        stored("STOPPED"); when(repo.runningFor("c")).thenReturn(guard.equals("running"));
        when(interactions.pendingQuestion("r")).thenReturn(guard.equals("question") ? question() : null);
        when(repo.pending("c")).thenReturn(guard.equals("pending")); when(repo.uncertain("r")).thenReturn(guard.equals("uncertain"));
        assertThatThrownBy(() -> service.resume("c", "r")).isInstanceOf(BusinessException.class); verifyNoInteractions(limiter, completion);
    }
    @Test void summaryKeepsTaskPausedAndDisablesToolsAndKnowledge() {
        stored("INTERRUPTED"); when(repo.uncertain("r")).thenReturn(true); when(repo.beginResume("r", "INTERRUPTED", true)).thenReturn(true);
        when(repo.receipts("r")).thenReturn("工具回执"); when(interactions.transcript("r")).thenReturn("用户决定");
        service.summarize("c", "r");
        assertThat(emitter.summaryOnly()).isTrue(); assertThat(emitter.recoveryContext()).contains("工具回执", "用户决定", "禁止执行");
        verify(completion).stream(eq("c"), contains("不得调用工具"), eq("r"), eq(false), eq(3), any(), eq(List.of()), same(emitter));
        emitter.publish("done", Map.of()); verify(repo).transition("r", "RUNNING", "INTERRUPTED");
        verify(interactions, never()).applySteering(anyString()); released();
    }
    @Test void steeringOverridesResumeAndCheckpointIsInstalled() {
        resumeAllowed("INTERRUPTED"); when(interactions.applySteering("r")).thenReturn(List.of(input("i", "STEER")));
        service.resume("c", "r");
        verify(completion).stream(eq("c"), contains("用户对当前任务的补充要求"), eq("r"), eq(true), eq(3), any(), anyList(), same(emitter));
        emitter.checkpoint(); verify(interactions).checkpoint("r");
    }
    @ParameterizedTest @CsvSource({"done,false,false,false,COMPLETED", "yield,false,false,false,INTERRUPTED",
            "done,true,false,false,WAITING_USER", "done,false,true,false,WAITING_CONFIRMATION", "done,false,false,true,INTERRUPTED"})
    void terminalEventsDetermineState(String event, boolean question, boolean pending, boolean unfinished, String status) {
        service.start("c", "new", request);
        when(interactions.pendingQuestion("new")).thenReturn(question ? question() : null);
        when(repo.pending("c")).thenReturn(pending); when(repo.unfinishedPlan("c")).thenReturn(unfinished);
        emitter.publish(event, Map.of()); verify(repo).transition("new", "RUNNING", status); verify(hub).signal("new"); released();
    }
    @Test void stopMasksErrorsAndIsIdempotentForTerminalRuns() {
        service.start("c", "new", request);
        when(repo.find("c", "new")).thenReturn(Optional.of(new Run("new", "c", json.toJson(request), "RUNNING")));
        service.stop("c", "new"); assertThat(emitter.stopped()).isTrue(); emitter.publish("error", new ChatStreamEvents.Error(50000, "private"));
        verify(repo).event(eq("new"), eq("error"), contains("任务已停止")); released();
        stored("COMPLETED"); service.stop("c", "r"); verify(repo, never()).transition("r", "RUNNING", "STOPPED");
    }
    @Test void modelFailureIsRecordedWithoutLeakingDetails() {
        resumeAllowed("STOPPED");
        doThrow(new IllegalStateException("synthetic secret")).when(completion).stream(anyString(), anyString(), anyString(), anyBoolean(), anyInt(), any(), anyList(), any());
        service.resume("c", "r");
        verify(repo).event(eq("r"), eq("error"), argThat(p -> p.contains("任务未能继续") && !p.contains("secret"))); released();
    }
    @Test void snapshotClampsCursorAndRequiresOwnership() {
        stored("STOPPED"); when(repo.uncertain("r")).thenReturn(true);
        assertThat(service.get("c", "r", -4).recoveryMode()).isEqualTo("SUMMARY_ONLY"); verify(repo).events("r", 0);
        assertThat(service.latest("c")).isNull(); assertThatThrownBy(() -> service.get("c", "missing", 0)).hasMessageContaining("不存在");
    }
    @ParameterizedTest @ValueSource(strings = {"RUNNING", "WAITING_CONFIRMATION", "COMPLETED"})
    void finishingConfirmationsNeverRestartsTheOriginalModel(String status) {
        stored(status);
        if (status.equals("RUNNING")) assertThatThrownBy(() -> service.finishConfirmations("c", "r")).hasMessageContaining("未在等待");
        else { service.finishConfirmations("c", "r"); if (!status.equals("COMPLETED")) verify(repo).transition("r", status, "COMPLETED"); }
        verifyNoInteractions(completion);
    }
    @Test void pendingConfirmationsCannotBeFinished() {
        stored("WAITING_CONFIRMATION"); when(repo.pending("c")).thenReturn(true);
        assertThatThrownBy(() -> service.finishConfirmations("c", "r")).hasMessageContaining("仍有待处理");
        verify(repo, never()).transition(anyString(), anyString(), anyString());
    }
    @ParameterizedTest @CsvSource({"true,false,WAITING_CONFIRMATION", "false,true,INTERRUPTED"})
    void answersDoNotReplayUnresolvedSideEffects(boolean pending, boolean uncertain, String status) {
        stored("WAITING_USER"); when(repo.pending("c")).thenReturn(pending); when(repo.uncertain("r")).thenReturn(uncertain);
        service.answer("c", "r", "q", "表格"); verify(repo).transition("r", "WAITING_USER", status); verifyNoInteractions(completion);
    }
    @Test void answersWaitForCheckpointAndRetriesDoNotRestartRunningTasks() {
        stored("RUNNING"); when(interactions.pendingQuestion("r")).thenReturn(question());
        assertThatThrownBy(() -> service.answer("c", "r", "q", "表格")).hasMessageContaining("保存进度");
        verify(interactions, never()).answer(anyString(), anyString(), anyString());
        when(interactions.pendingQuestion("r")).thenReturn(null); service.answer("c", "r", "q", "表格"); verifyNoInteractions(completion);
    }
    @Test void answerResumesWithExplicitHumanInput() {
        resumeAllowed("WAITING_USER"); service.answer("c", "r", "q", "  表格  ");
        verify(completion).stream(eq("c"), eq("用户对澄清问题的回答：表格"), eq("r"), anyBoolean(), anyInt(), any(), anyList(), any());
    }
    @Test void recoveryPreservesHumanInterventionAndHonorsWorkerRoles() {
        ReflectionTestUtils.invokeMethod(service, "configureRole", new RuntimeRole("index")); service.recover(); service.dispatchWaiting(); verifyNoInteractions(repo);
        ReflectionTestUtils.invokeMethod(service, "configureRole", new RuntimeRole("api"));
        var q = new Run("q-run", "c1", "{}", "RUNNING"); var p = new Run("p-run", "c2", "{}", "RUNNING");
        var u = new Run("u-run", "c3", "{}", "RUNNING"); var s = new Run("s-run", "c4", "{}", "RUNNING");
        when(repo.recoveryCandidates()).thenReturn(List.of(q,p,u,s)); when(interactions.pendingQuestion(q.id())).thenReturn(question());
        when(repo.pending("c2")).thenReturn(true); when(repo.uncertain(u.id())).thenReturn(true); when(repo.summaryAttempt(s.id())).thenReturn(true);
        service.recover(); verify(repo).transition(q.id(), "INTERRUPTED", "WAITING_USER"); verify(repo).transition(p.id(), "INTERRUPTED", "WAITING_CONFIRMATION");
        for (Run r : List.of(q,p,u,s)) verify(interactions).restore(r.id()); verifyNoInteractions(completion);
    }
    @Test void recoveryRetriesQuotaAndDiscardsMissingDeferredRuns() {
        stored("INTERRUPTED"); when(repo.recoveryCandidates()).thenReturn(List.of(run("RUNNING"))); when(limiter.tryAcquire()).thenReturn(false);
        service.recover(); verifyNoInteractions(completion); when(limiter.tryAcquire()).thenReturn(true); when(repo.beginResume("r", "INTERRUPTED", false)).thenReturn(true);
        service.dispatchWaiting(); assertThat(emitter).isNotNull(); emitter.complete();
        var deferred = new java.util.concurrent.ConcurrentHashMap<String,String>(); deferred.put("missing","c");
        ReflectionTestUtils.setField(service, "deferredRecovery", deferred); service.dispatchWaiting(); assertThat(deferred).isEmpty();
    }
    @Test void queuedInputWithExistingRunIsOnlyMarkedApplied() {
        when(repo.latest("c")).thenReturn(Optional.of(run("COMPLETED"))); when(interactions.queuedConversations()).thenReturn(List.of("c"));
        when(interactions.nextQueued("c")).thenReturn(Optional.of(input("i","QUEUE"))).thenReturn(Optional.empty());
        when(repo.find("c","i")).thenReturn(Optional.of(new Run("i","c",json.toJson(request),"COMPLETED")));
        ReflectionTestUtils.setField(service, "queuedWork", true); service.dispatchWaiting(); verify(interactions).applied("i"); verifyNoInteractions(completion);
    }
    @Test void followupMutationsPreserveIdentityAndRejectStaleTargets() {
        stored("STOPPED"); when(repo.runningFor("c")).thenReturn(true);
        assertThatThrownBy(() -> service.followup("c","r","i","STEER",request)).hasMessageContaining("其他任务"); when(repo.runningFor("c")).thenReturn(false);
        assertThatThrownBy(() -> service.followup("c","r","x".repeat(65),"QUEUE",request)).hasMessageContaining("过长");
        stored("RUNNING"); service.followup("c","r","i","QUEUE",request); service.cancelFollowup("c","r","i"); service.editFollowup("c","r","i","修改");
        verify(interactions).submit(input("i","QUEUE")); verify(interactions).cancel("c","i"); verify(interactions).edit("c","i","修改");
        when(repo.active("c")).thenReturn(Optional.of(run("RUNNING"))); service.steerFollowup("c","r","i"); verify(interactions).steer("c","i","r");
        when(repo.active("c")).thenReturn(Optional.empty()); when(repo.latest("c")).thenReturn(Optional.of(run("STOPPED"))); when(repo.uncertain("r")).thenReturn(true);
        assertThatThrownBy(() -> service.steerFollowup("c","r","i")).hasMessageContaining("未核实");
    }

    @ParameterizedTest @ValueSource(strings={"RUNNING","uncertain","summary","quota","conflict","ok"})
    void deferredRecoveryIsRetainedOnlyWhileEligible(String state) {
        var deferred=new java.util.concurrent.ConcurrentHashMap<String,String>();deferred.put("r","c");
        ReflectionTestUtils.setField(service,"deferredRecovery",deferred);
        stored(state.equals("RUNNING")?"RUNNING":"INTERRUPTED");
        when(repo.uncertain("r")).thenReturn(state.equals("uncertain"));when(repo.summaryAttempt("r")).thenReturn(state.equals("summary"));
        if(state.equals("quota"))when(limiter.tryAcquire()).thenReturn(false);
        if(state.equals("conflict"))when(repo.runningFor("c")).thenReturn(true);
        service.dispatchWaiting();
        assertThat(deferred.containsKey("r")).isEqualTo(state.equals("quota"));released();
    }
    @Test void startupRecoveryPropagatesNonQuotaConflicts() {
        stored("INTERRUPTED");when(repo.recoveryCandidates()).thenReturn(List.of(run("RUNNING")));
        when(repo.runningFor("c")).thenReturn(true);
        assertThatThrownBy(service::recover).hasMessageContaining("其他任务");verifyNoInteractions(completion);
    }
    @ParameterizedTest @CsvSource({"COMPLETED,false,false","COMPLETED,true,false","COMPLETED,false,true","INTERRUPTED,false,false","WAITING_USER,false,false"})
    void lateSteeringResumesOnlyWhenHumanDependenciesAreClear(String status,boolean question,boolean pending) {
        stored(status);when(interactions.pendingQuestion("r")).thenReturn(question?question():null);when(repo.pending("c")).thenReturn(pending);
        when(repo.transition("r",status,"INTERRUPTED")).thenReturn(false);
        service.followup("c","r",null,"STEER",request);
        verify(interactions).submit(argThat(v-> v.id()!=null && !v.id().isBlank() && v.mode().equals("STEER")));
        verifyNoInteractions(completion);
    }
    @ParameterizedTest @CsvSource({"false,false,true","true,false,true","false,true,true","false,false,false"})
    void steeringExistingInputPreservesQuestionAndConfirmationGuards(boolean question,boolean pending,boolean applied) {
        stored("STOPPED");when(repo.active("c")).thenReturn(Optional.of(run("STOPPED")));
        when(interactions.steer("c","i","r")).thenReturn(applied);
        when(interactions.pendingQuestion("r")).thenReturn(question?question():null);when(repo.pending("c")).thenReturn(pending);
        if(!question && !pending && applied)when(repo.beginResume("r","STOPPED",false)).thenReturn(true);
        service.steerFollowup("c","r","i");
        if(question||pending||!applied)verifyNoInteractions(completion);else assertThat(emitter).isNotNull();
    }
    @Test void stopWithoutLocalWorkerAndGeneratedStartKeys() {
        stored("RUNNING");service.stop("c","r");verify(repo).transition("r","RUNNING","STOPPED");
        for(String key:Arrays.asList(null," ")) {service.start("generated",key,request);emitter.complete();}
        verify(repo,times(2)).create(anyString(),eq("generated"),anyString());released();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void recoveredCompletionPreservesUnknownSideEffects(boolean uncertain) {
        service.start("c","new",request);emitter.recovered(true);when(repo.uncertain("new")).thenReturn(uncertain);
        emitter.publish("done",Map.of());verify(repo).transition("new","RUNNING",uncertain?"INTERRUPTED":"COMPLETED");released();
    }
}
