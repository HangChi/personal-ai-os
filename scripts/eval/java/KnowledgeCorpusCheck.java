package evaluation;

import com.personalos.knowledge.splitter.MarkdownStructureTextSplitter;
import com.personalos.knowledge.service.BoundedEmbeddingBatchingStrategy;
import java.nio.file.*;
import java.util.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import tools.jackson.databind.ObjectMapper;

/** Offline preflight using the exact product splitter and batching strategy. No model calls. */
public class KnowledgeCorpusCheck {
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();
        var estimator=new JTokkitTokenCountEstimator();
        try(var paths=Files.list(Path.of(args[0]))) {
            for(var path:paths.filter(p->p.toString().endsWith(args.length>1?args[1]:".md")).sorted().toList()) {
                var row=new LinkedHashMap<String,Object>();
                row.put("filename",path.getFileName().toString());
                row.put("sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
                try {
                    var chunks=new MarkdownStructureTextSplitter(1200,350,0).transform(List.of(new Document(Files.readString(path))));
                    row.put("chunks",chunks.size());
                    row.put("max_chunk_chars",chunks.stream().mapToInt(d->d.getText().length()).max().orElse(0));
                    row.put("max_estimated_tokens",chunks.stream().mapToInt(d->estimator.estimate(d.getText())).max().orElse(0));
                    new BoundedEmbeddingBatchingStrategy().batch(chunks);
                    row.put("local_batch_valid",true);
                } catch(RuntimeException ex) {
                    row.put("local_batch_valid",false);
                    row.put("error_type",ex.getClass().getSimpleName());
                    row.put("error",ex.getMessage());
                }
                System.out.println(json.writeValueAsString(row));
            }
        }
    }
}
