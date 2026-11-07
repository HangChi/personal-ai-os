package com.personalos.task.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.exception.BusinessException;
import com.personalos.task.dto.*;
import com.personalos.task.entity.*;
import com.personalos.task.repository.TaskRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class TaskOptionalFieldsTest {
 @Test void patchChangesExplicitFieldsAndClearsOnlyExplicitWorkspaceLinks(){
  var repo=mock(TaskRepository.class);var service=new TaskService(repo);var id=UUID.randomUUID();
  var entity=new TaskEntity("old","old description",TaskPriority.LOW,null,Instant.now());
  when(repo.findById(id)).thenReturn(Optional.of(entity));when(repo.save(any())).thenAnswer(call->call.getArgument(0));
  UUID project=UUID.randomUUID(),parent=UUID.randomUUID();Instant due=Instant.now().plusSeconds(3600);
  service.update(id,new UpdateTaskRequest(" new "," description ",TaskPriority.HIGH,due,false,TaskStatus.DOING,List.of("tag"),project,parent,"NONE","UTC",false,false));
  assertThat(entity.getTitle()).isEqualTo("new");assertThat(entity.getDescription()).isEqualTo("description");
  assertThat(entity.getPriority()).isEqualTo(TaskPriority.HIGH);assertThat(entity.getDueAt()).isEqualTo(due);
  assertThat(entity.getProjectId()).isEqualTo(project);assertThat(entity.getParentTaskId()).isEqualTo(parent);
  service.update(id,new UpdateTaskRequest(null,null,null,null,null,null,null,null,null,null,null,false,false));
  assertThat(entity.getProjectId()).isEqualTo(project);assertThat(entity.getParentTaskId()).isEqualTo(parent);
  service.update(id,new UpdateTaskRequest(null,null,null,null,true,null,null,null,null,null,null,true,true));
  assertThat(entity.getProjectId()).isNull();assertThat(entity.getParentTaskId()).isNull();assertThat(entity.getDueAt()).isNull();
  assertThat(service.findByIds(List.of())).isEmpty();assertThat(service.deleteAll(null)).isZero();assertThat(service.deleteAll(List.of())).isZero();
  assertThatThrownBy(()->service.requireTask(null)).isInstanceOf(BusinessException.class);
 }
 @Test void recurringTaskRejectsInvalidZoneRuleMissingDueAndSubtaskCombination(){
  for(String kind:List.of("rule","zone","due","parent")){
   var repo=mock(TaskRepository.class);var service=new TaskService(repo);
   var request=new CreateTaskRequest("task",null,null,kind.equals("due")?null:Instant.now(),null,null,kind.equals("parent")?UUID.randomUUID():null,kind.equals("rule")?"HOURLY":"DAILY",kind.equals("zone")?"Bad/Zone":"UTC");
   assertThatThrownBy(()->service.create(request)).as(kind).isInstanceOf(BusinessException.class);verifyNoInteractions(repo);
  }
 }

 @Test void workspaceFiltersAreIncludedInBothCountQueryTypes(){
  var repo=mock(TaskRepository.class);var service=new TaskService(repo);
  when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class),any(org.springframework.data.domain.Pageable.class))).thenAnswer(call->{
   org.springframework.data.jpa.domain.Specification<TaskEntity> spec=call.getArgument(0);
   for(Class<?> type:List.of(Long.class,long.class)){
    var root=mock(jakarta.persistence.criteria.Root.class);var criteria=mock(jakarta.persistence.criteria.CriteriaQuery.class);var cb=mock(jakarta.persistence.criteria.CriteriaBuilder.class,RETURNS_DEEP_STUBS);
    doReturn(type).when(criteria).getResultType();spec.toPredicate(root,criteria,cb);
    verify(cb).notEqual(any(),eq(TaskStatus.DONE));
    verify(cb).greaterThanOrEqualTo(any(),any(Instant.class));verify(cb).lessThan(any(),any(Instant.class));
   }
   return org.springframework.data.domain.Page.empty();
  });
  service.search(new TaskQuery(TaskStatus.TODO,"word","tag",0,20,UUID.randomUUID(),UUID.randomUUID(),true,Instant.EPOCH,Instant.now()));
 }
}
