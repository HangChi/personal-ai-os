import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import tools.jackson.databind.json.JsonMapper;

public final class EmbeddingContentOracle {
    public static void main(String[] arguments) throws Exception {
        var json = JsonMapper.builder().build();
        if (arguments.length == 1 && arguments[0].equals("--fixture")) {
            var metadata = new HashMap<String, Object>();
            metadata.put("document_id", "owned-id");
            metadata.put("document_title", "title");
            metadata.put("filename", "real.md");
            metadata.put("chunk_index", 7);
            System.out.println("ORACLE " + json.writeValueAsString(
                new Document("synthetic content", metadata).getFormattedContent(MetadataMode.EMBED)));
            return;
        }
        var hashes = new ArrayList<String>();
        for (var row : json.readTree(System.in)) {
            var metadata = json.convertValue(row.get("metadata"), HashMap.class);
            var document = new Document(row.get("content").asText(), metadata);
            var content = document.getFormattedContent(MetadataMode.EMBED);
            hashes.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8))));
        }
        System.out.println("ORACLE " + json.writeValueAsString(hashes));
    }
}
