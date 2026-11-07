package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.common.ai.config.dto.*;
import com.personalos.common.json.JsonCodec;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class ModelProfileControllerBoundaryTest {
 final ModelProfileService profiles=mock(ModelProfileService.class);final ModelConfigService config=mock(ModelConfigService.class);final ModelListProbeService probe=mock(ModelListProbeService.class);
 final ModelProfileController controller=new ModelProfileController(profiles,config,mock(ModelConnectionTestService.class),probe);
 final JsonCodec json=new JsonCodec(JsonMapper.builder().build());
 SaveModelProfileRequest request(Map<String,Object> changes){var map=new HashMap<String,Object>(changes);map.put("name","fixture");return json.fromJson(json.toJson(map),SaveModelProfileRequest.class);}
 @Test void validatesFieldLengthsListSizesTemperatureAndEndpointBeforePersistence(){
  assertThatThrownBy(()->controller.create(null)).hasMessageContaining("请求体");
  for(var field:Map.of("baseUrl",500,"apiKey",500,"chatModel",120,"embeddingModel",120,"presetId",60,"protocol",20).entrySet())assertThatThrownBy(()->controller.create(request(Map.of(field.getKey(),"x".repeat(field.getValue()+1))))).hasMessageContaining("长度不能超过");
  for(var field:List.of("chatModels","embeddingModels","audioModels")){assertThatThrownBy(()->controller.create(request(Map.of(field,Collections.nCopies(501,"m"))))).hasMessageContaining("最多保存");assertThatThrownBy(()->controller.create(request(Map.of(field,List.of("m".repeat(121)))))).hasMessageContaining("长度不能超过");}
  for(double temp:new double[]{-0.1,2.1})assertThatThrownBy(()->controller.create(request(Map.of("temperature",temp)))).hasMessageContaining("温度");
  assertThatThrownBy(()->controller.create(request(Map.of("baseUrl","file:///secret")))).hasMessageContaining("http");verifyNoInteractions(profiles);
 }
 @Test void validBoundariesPreserveModelKindsWindowsAndMaskCredentials(){
  UUID id=UUID.randomUUID();var entity=new AiModelProfileEntity("fixture",null,0,Instant.now());ReflectionTestUtils.setField(entity,"id",id);
  when(profiles.create(any())).thenReturn(entity);when(config.resolveFrom(entity)).thenReturn(new ModelConfigService.ResolvedConfig("https://example.test","sk-private-1234","chat",0.5,"embed","audio",1,Instant.now(),Map.of()));
  var models=List.of(new AiModelProfileModelEntity(id,AiModelProfileModelEntity.Kind.CHAT,"chat",0,8192,Instant.now()),new AiModelProfileModelEntity(id,AiModelProfileModelEntity.Kind.CHAT,"unknown",1,Instant.now()),new AiModelProfileModelEntity(id,AiModelProfileModelEntity.Kind.EMBEDDING,"embed",0,2048,Instant.now()),new AiModelProfileModelEntity(id,AiModelProfileModelEntity.Kind.AUDIO,"audio",0,Instant.now()));
  when(profiles.modelsOf(id)).thenReturn(models);
  for(Object base:Arrays.asList(null,""," ","http://example.test"," https://example.test "))for(Object temperature:Arrays.asList(null,0.0,2.0)){var changes=new HashMap<String,Object>();changes.put("baseUrl",base);changes.put("temperature",temperature);changes.put("chatModels",Arrays.asList(null,"x".repeat(120)));var dto=controller.create(request(changes)).data();assertThat(dto.contextWindows()).containsExactlyEntriesOf(Map.of("chat",8192));assertThat(dto.audioModel()).isEqualTo("audio");assertThat(dto.chatModels()).containsExactly("chat","unknown");assertThat(dto.apiKeyMasked()).isEqualTo("****1234");}
  when(profiles.modelsOf(id)).thenReturn(null);assertThat(controller.create(request(Map.of())).data().chatModels()).isEmpty();
 }
 @Test void probesFallbackToSelectedProfileOnlyWhenDraftKeyIsBlank(){
  assertThatThrownBy(()->controller.probeWindow(null)).hasMessageContaining("请求体");UUID id=UUID.randomUUID();var entity=new AiModelProfileEntity("saved",null,0,Instant.now());when(profiles.require(id)).thenReturn(entity);when(config.resolveFrom(entity)).thenReturn(new ModelConfigService.ResolvedConfig("https://saved.test","saved-key","m",0.5,"e",1,Instant.now(),Map.of()));
  for(String key:Arrays.asList(null,""," "))for(String base:Arrays.asList(null,""," ","https://draft.test"))controller.probeWindow(new ProbeWindowRequest("OPENAI",base,key,id,"m"));
  verify(probe,times(9)).probeWindow(ModelProtocol.OPENAI,"https://saved.test","saved-key","m");verify(probe,times(3)).probeWindow(ModelProtocol.OPENAI,"https://draft.test","saved-key","m");
  controller.probeWindow(new ProbeWindowRequest("OPENAI","https://draft.test","draft-key",id,"m"));verify(probe).probeWindow(ModelProtocol.OPENAI,"https://draft.test","draft-key","m");
 }
}
