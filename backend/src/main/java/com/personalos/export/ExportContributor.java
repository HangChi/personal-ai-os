package com.personalos.export;

import tools.jackson.core.JsonGenerator;

/** 各模块以 DTO 分批写入自己的顶层数组，返回实际写出的条数。 */
public interface ExportContributor {
    String key();
    long writeTo(JsonGenerator json);
}
