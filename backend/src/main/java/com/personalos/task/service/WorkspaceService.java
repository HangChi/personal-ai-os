package com.personalos.task.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.text.Texts;
import com.personalos.task.dto.*;
import com.personalos.task.repository.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceService {
    private final WorkspaceRepository repository;
    public WorkspaceService(WorkspaceRepository repository){this.repository=repository;}
    @Transactional(readOnly=true)
    public List<ProjectDto> projects(){return repository.projects();}
    @Transactional(readOnly=true)
    public List<ProjectDto> exportBatch(int page,int size){return repository.exportBatch(page,size);}
    @Transactional
    public ProjectDto save(UUID id,ProjectChange change){
        String name=change.name();
        if(id==null || name!=null) name=Texts.requireNotBlank(name,"项目名称",100);
        if(change.description()!=null && change.description().length()>2000)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,"项目描述不能超过 2000 字");
        var p=repository.save(id,new ProjectChange(name,change.description(),change.archived()));
        if(p==null) throw new ResourceNotFoundException("项目不存在");
        return p;
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public WorkspaceRepository.RunPage unfinishedRuns(){return repository.unfinishedRuns();}
}
