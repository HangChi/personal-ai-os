package com.personalos.task;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.task.entity.*;
import com.personalos.task.repository.TaskRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.*;
class TaskSearchBoundaryTest {
 @Test void searchLabelsEveryStatusAndPriorityAndExecutesEscapedFilter(){
  var repo=mock(TaskRepository.class);var tasks=new ArrayList<TaskEntity>();
  for(TaskStatus s:TaskStatus.values())for(TaskPriority p:TaskPriority.values()){
   var task=mock(TaskEntity.class);when(task.getId()).thenReturn(UUID.randomUUID());when(task.getTitle()).thenReturn(s==TaskStatus.TODO?"needle title":"other");when(task.getStatus()).thenReturn(s);when(task.getPriority()).thenReturn(p);tasks.add(task);
  }
  when(repo.findAll(any(Specification.class),any(Pageable.class))).thenAnswer(call->{
   Specification<TaskEntity> spec=call.getArgument(0);var root=mock(Root.class);var cb=mock(CriteriaBuilder.class,RETURNS_DEEP_STUBS);spec.toPredicate(root,mock(CriteriaQuery.class),cb);
   return new PageImpl<>(tasks);
  });
  var hits=new TaskSearchContributor(repo).search("needle",20);
  assertThat(hits).hasSize(tasks.size());
  assertThat(hits.toString()).contains("已完成","进行中","待办","高","中","低","标题","描述");
 }
}
