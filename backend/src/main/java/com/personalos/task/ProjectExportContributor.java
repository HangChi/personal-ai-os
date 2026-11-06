package com.personalos.task;

import com.personalos.export.ExportContributor;
import com.personalos.export.ExportWriter;
import com.personalos.task.service.WorkspaceService;
import tools.jackson.core.JsonGenerator;
import org.springframework.stereotype.Component;

@Component
public class ProjectExportContributor implements ExportContributor {
    private final WorkspaceService service;
    private final ExportWriter writer;
    public ProjectExportContributor(WorkspaceService service,ExportWriter writer){this.service=service;this.writer=writer;}
    public String key(){return "projects";}
    public long writeTo(JsonGenerator json){return writer.array(json,page->service.exportBatch(page,ExportWriter.BATCH_SIZE));}
}
