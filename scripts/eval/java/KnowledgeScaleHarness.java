package evaluation;

import com.personalos.PersonalOsApplication;
import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.api.ApiResponse;
import com.personalos.knowledge.dto.KnowledgeAskResponse;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.governance.KnowledgeGovernance;
import com.personalos.knowledge.service.KnowledgeQaService;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import java.util.List;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 仅用于隔离测评；不包含在产品 JAR 中，也不注册到现有服务。 */
public class KnowledgeScaleHarness {
    public static void main(String[] args) {
        if (!"true".equals(System.getenv("KNOWLEDGE_SCALE_ISOLATED_EVAL")))
            throw new IllegalStateException("Explicit isolated evaluation is required");
        new SpringApplicationBuilder(PersonalOsApplication.class, Candidates.class).run(args);
    }
    @RestController
    static class Candidates {
        private final KnowledgeRetrievalService recall;
        private final KnowledgeGovernance governance;
        private final AiChatClientProvider ai;
        Candidates(KnowledgeRetrievalService recall, KnowledgeGovernance governance, AiChatClientProvider ai) {
            this.recall = recall; this.governance = governance; this.ai = ai;
        }
        record Query(String query) {}
        record Answer(String question, List<KnowledgeHitDto> hits) {}
        @PostMapping("/api/v1/knowledge/eval/candidates")
        ApiResponse<List<KnowledgeHitDto>> candidates(@RequestBody Query q) {
            return ApiResponse.ok(governance.enrich(recall.candidates(q.query(), 40)).stream().limit(40).toList());
        }
        @PostMapping("/api/v1/knowledge/eval/answer")
        ApiResponse<KnowledgeAskResponse> answer(@RequestBody Answer q) {
            if (q.hits() == null || q.hits().size() > 20) throw new IllegalArgumentException("Invalid evidence count");
            // 使用冻结证据，复用实际业务生成提示词与引用组装。
            return ApiResponse.ok(new KnowledgeQaService((query, topK) -> q.hits(), ai).ask(q.question(), q.hits().size()));
        }
    }
}
