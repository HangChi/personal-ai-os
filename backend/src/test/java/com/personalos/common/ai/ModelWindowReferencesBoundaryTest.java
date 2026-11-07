package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModelWindowReferencesBoundaryTest {
 @Test void providerMatchingAndConservativeInputLimitAreDeterministic(){
  assertThat(ModelWindowReferences.find(null,null)).isNull();
  for(String base:Arrays.asList(null,"bad uri","relative","https://gateway.test")){
   assertThat(ModelWindowReferences.find("unknown",base)).isNull();
   assertThat(ModelWindowReferences.find("vendor/cmaas-deepseek-v4-pro-20261031",base).configurationWindow()).isEqualTo(1048576);
  }
  for(String host:List.of("dashscope.aliyuncs.com","region.dashscope.aliyuncs.com","dashscope-intl.aliyuncs.com")){
   for(String model:List.of("qwen3.8-flash","deepseek-v4.1-flash","glm-5.3","minimax-m2.5","deepseek-v4-pro"))assertThat(ModelWindowReferences.find(model,"https://"+host).configurationWindow()).isGreaterThan(200000);
  }
  assertThat(ModelWindowReferences.find("qwen3.8-flash","https://dashscope.aliyuncs.com").configurationWindow()).isEqualTo(983616);
  for(String host:List.of("evil-dashscope.aliyuncs.com.test","dashscope-.test","dashscope-intl.other.com"))assertThat(ModelWindowReferences.find("qwen3.8-flash","https://"+host)).isNull();
 }
}
