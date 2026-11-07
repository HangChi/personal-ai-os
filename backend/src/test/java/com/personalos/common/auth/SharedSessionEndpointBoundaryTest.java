package com.personalos.common.auth;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class SharedSessionEndpointBoundaryTest {
 @Test void dedicatedRedisEndpointsAreValidatedWithoutOpeningConnections(){
  var c=new SharedSessionConfig();
  for(String uri:java.util.Arrays.asList(null,"bad","redis:///","http://localhost","redis://localhost?x=y","redis://localhost#x"))
   assertThatThrownBy(()->c.sessionRedisConnection(uri)).isInstanceOf(IllegalArgumentException.class);
  for(String uri:java.util.List.of("redis://localhost","redis://localhost/","redis://:password@localhost:6380/2","rediss://user:password@localhost/1","redis://password@localhost")){
   var f=c.sessionRedisConnection(uri);try{
    assertThat(f.getHostName()).isEqualTo("localhost");assertThat(f.getClientConfiguration().isUseSsl()).isEqualTo(uri.startsWith("rediss"));
   }finally{f.destroy();}
  }
  assertThat(c.sessionCookieSerializer(true)).isNotNull();assertThat(c.sessionCookieSerializer(false)).isNotNull();
 }
}
