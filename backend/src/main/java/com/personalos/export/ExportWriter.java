package com.personalos.export;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonGenerator;

/** 逐批写入响应，并释放已读取实体，内存不随导出总量增长。 */
@Component
public class ExportWriter {
    public static final int BATCH_SIZE = 200;
    private final EntityManager entities;

    public ExportWriter(EntityManager entities) {
        this.entities = entities;
    }

    public <T> long array(JsonGenerator json, IntFunction<? extends List<T>> load) {
        return array(json, load, (writer, value) -> writer.writePOJO(value));
    }

    public <T> long array(JsonGenerator json, IntFunction<? extends List<T>> load,
                          BiConsumer<JsonGenerator, T> write) {
        json.writeStartArray();
        long count = 0;
        for (int page = 0; ; page++) {
            List<T> batch = load.apply(page);
            for (T item : batch) {
                write.accept(json, item);
            }
            count += batch.size();
            entities.clear();
            json.flush();
            if (batch.size() < BATCH_SIZE) break;
        }
        json.writeEndArray();
        return count;
    }
}
