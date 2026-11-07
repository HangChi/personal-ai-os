package com.personalos.common.ai.config;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.personalos.common.ai.*;
import org.junit.jupiter.api.Test;
class ContextWindowRegistrarBoundaryTest {
 @Test void onlyCurrentChatModelsContributeValidWindows(){
  var profiles=mock(ModelProfileService.class);var config=mock(ModelConfigService.class);var budget=new ContextBudgetService(new AiProperties(null,null));new ContextWindowRegistrar(budget,profiles,config);
  when(profiles.current()).thenReturn(Optional.empty());assertThat(budget.modelWindow("private").source()).isEqualTo("fallback");
  var active=mock(AiModelProfileEntity.class);var id=UUID.randomUUID();when(active.getId()).thenReturn(id);when(profiles.current()).thenReturn(Optional.of(active));var rows=new ArrayList<AiModelProfileModelEntity>();
  for(int i=0;i<6;i++){var row=mock(AiModelProfileModelEntity.class);when(row.kindEnum()).thenReturn(i==0?AiModelProfileModelEntity.Kind.EMBEDDING:AiModelProfileModelEntity.Kind.CHAT);when(row.getModelName()).thenReturn(i==1?"other":"private");when(row.getContextWindow()).thenReturn(i==2?null:i==3?1023:8192);rows.add(row);}
  when(profiles.modelsOf(id)).thenReturn(rows);assertThat(budget.contextWindowTokens("private")).isEqualTo(8192);
  when(profiles.current()).thenThrow(new IllegalStateException());assertThat(budget.modelWindow("private").source()).isEqualTo("fallback");
 }
}
