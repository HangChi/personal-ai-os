import com.personalos.common.ai.config.*;
import com.personalos.common.ai.config.dto.ModelConfigDto;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 只运行原配置解析/DTO/加密转换器；不启动应用，不创建模型或数据库连接。 */
public class ModelConfigReadOracle {
    private static final String PLAIN = "synthetic-java-go-key-密😀abcd";
    private static final String MASTER = Base64.getEncoder().encodeToString(new byte[] {
        1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1 });
    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public static void main(String[] args) throws Exception {
        var converter = new ModelKeyConverter(MASTER);
        if (args.length == 2 && args[0].equals("decrypt-go")) {
            if (!PLAIN.equals(converter.convertToEntityAttribute(args[1]))) {
                throw new IllegalStateException("Go/Java synthetic ciphertext mismatch");
            }
            System.out.println("ORACLE GO_ROUNDTRIP_OK");
            return;
        }
        var mapper = JsonMapper.builder().build();
        JsonNode input = mapper.readTree(System.in);
        var output = new ArrayList<Map<String, Object>>();
        for (JsonNode test : input.get("cases")) {
            var env = new MockEnvironment();
            test.get("variables").properties().forEach(entry -> env.withProperty(entry.getKey(), entry.getValue().asText()));
            var p = test.get("properties");
            var service = new ModelConfigService(null, null, null, Clock.fixed(NOW, ZoneOffset.UTC), null, env,
                text(p,"spring.ai.openai.base-url"), text(p,"spring.ai.openai.api-key"),
                text(p,"spring.ai.openai.chat.model"), text(p,"spring.ai.openai.embedding.model"),
                text(p,"spring.ai.openai.chat.temperature"));
            AiModelProfileEntity profile = null;
            JsonNode db = test.get("values");
            if (db != null && !db.isNull()) {
                profile = new AiModelProfileEntity("synthetic", null, 0, NOW);
                String rawTemperature = text(db,"temperature");
                profile.overwrite("synthetic", null, text(db,"baseUrl"), text(db,"apiKey"), text(db,"chatModel"),
                    rawTemperature == null ? null : Double.valueOf(rawTemperature), text(db,"embeddingModel"),
                    text(db,"audioModel"), "OPENAI", NOW);
            }
            var r = service.resolveFrom(profile);
            var dto = new ModelConfigDto(r.baseUrl(), ModelConfigService.mask(r.apiKey()), r.hasApiKey(),
                r.chatModel(), r.temperature(), r.embeddingModel(), r.revision(), r.updatedAt(), r.sources(), true, false);
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("name",text(test,"name")); row.put("dto",dto);
            row.put("audioModel",r.audioModel()); row.put("hasAudioModel",r.hasAudioModel());
            output.add(row);
        }
        String stored = text(input,"javaCiphertext");
        if (stored == null) stored = converter.convertToDatabaseColumn(PLAIN);
        if (!PLAIN.equals(converter.convertToEntityAttribute(stored))) throw new IllegalStateException("Java fixture invalid");
        var masks = new LinkedHashMap<String,String>();
        for (JsonNode key : input.get("maskInputs")) masks.put(key.asText(),ModelConfigService.mask(key.asText()));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("cases",output); result.put("masks",masks);
        result.put("javaCiphertext",stored); result.put("syntheticPlaintext",PLAIN); result.put("syntheticMaster",MASTER);
        System.out.println("ORACLE " + mapper.writeValueAsString(result));
    }
}
