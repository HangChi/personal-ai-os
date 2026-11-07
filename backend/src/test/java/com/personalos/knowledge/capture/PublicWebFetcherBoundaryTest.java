package com.personalos.knowledge.capture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.exception.BusinessException;
import java.net.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
class PublicWebFetcherBoundaryTest {
 @Test void canonicalRejectsCredentialsReservedNamesAndMalformedUrls() {
  for(String s:Arrays.asList(null,"x".repeat(2049),"https://example.com/中","https://example.com/"+(char)9,"https://exa mple.com","//example.com","ftp://example.com","https://u:p@example.com","http://example.com:443","https://example.com:80","http://localhost","http://a.localhost","http://x.local","http://x.internal","http://single","http://[2001:4860::1]")) assertThatThrownBy(()->PublicWebFetcher.canonical(s)).as(String.valueOf(s)).isInstanceOf(BusinessException.class);
  Map<String,String> cases=Map.of(" HTTPS://EXAMPLE.COM.:443 	","https://example.com/","http://example.com:80/a/./b/../","http://example.com/a/","https://example.com/../x//%2e/%2f?q=a%20b#f","https://example.com/x//%2e/%2f?q=a%20b","https://example.com/a/.","https://example.com/a/");
  for(var e:cases.entrySet()) {if(e.getKey().contains(String.valueOf((char)9))) continue;assertThat(PublicWebFetcher.canonical(e.getKey()).toString()).isEqualTo(e.getValue());}
  assertThat(PublicWebFetcher.canonical(" HTTPS://EXAMPLE.COM.:443 " )).hasToString("https://example.com/");
 }
 @Test void reservedAddressRangesAreDeniedAtTheirBoundaries() throws Exception {
  for(String ip:List.of("0.1.1.1","10.0.0.1","127.0.0.1","224.0.0.1","255.255.255.255","100.64.0.1","100.127.255.255","169.254.1.1","172.16.0.1","172.31.255.255","192.0.1.1","192.168.1.1","192.2.1.1","192.88.99.1","198.18.1.1","198.19.1.1","198.51.100.1","203.0.113.1","::1","fe80::1","fc00::1","2001:0::1","2001:1ff::1","2001:db8::1","2002::1","3fff:0::1","3fff:f0::1")) assertThat(PublicWebFetcher.publicAddress(InetAddress.getByName(ip))).as(ip).isFalse();
  for(String ip:List.of("1.1.1.1","100.63.255.255","100.128.0.1","169.253.1.1","172.15.255.255","172.32.0.1","192.88.98.1","192.88.100.1","192.1.1.1","198.51.99.1","198.51.101.1","198.17.1.1","203.0.112.1","203.1.113.1","223.255.255.255","2001:200::1","2001:db7::1","2001:db9::1","2003::1","3000::1","3fff:1000::1","3ffe::1")) assertThat(PublicWebFetcher.publicAddress(InetAddress.getByName(ip))).as(ip).isTrue();
 }
 @Test void extractionPreservesBodyAndLimitsTitle() throws Exception {
  var p=PublicWebFetcher.extract("https://example.com/p","<html><head><title> A   B </title><style>hidden-style</style></head><body><p>Visible<br>tail</p><script>hidden-script</script></body></html>",true);
  assertThat(p.title()).isEqualTo("A B");assertThat(p.content()).contains("Visible","tail").doesNotContain("hidden");
  assertThat(PublicWebFetcher.extract("https://example.com","hello",false).title()).isEqualTo("example.com");
  assertThat(PublicWebFetcher.extract("https://example.com","<title>"+"x".repeat(201)+"</title><p>body</p>",true).title()).hasSize(200);
  for(String body:List.of("  ","x".repeat(1048577))) assertThatThrownBy(()->PublicWebFetcher.extract("https://example.com",body,false)).isInstanceOf(BusinessException.class);
  var f=new PublicWebFetcher();try {assertThatThrownBy(()->f.fetch("http://127.0.0.1")).isInstanceOf(BusinessException.class).hasMessageContaining("禁止");}finally{f.close();}
  assertThatThrownBy(()->f.fetch("https://example.com")).isInstanceOf(BusinessException.class).hasMessageContaining("繁忙");
 }
 @Test void boundHttpResponsesEnforceMediaEncodingAndSize() throws Exception {
  var method=PublicWebFetcher.class.getDeclaredMethod("fetchBound",URI.class,HttpGet.class); method.setAccessible(true);
  var uri=URI.create("https://8.8.8.8/");
  for(String test:List.of("status","empty","null-type","json","latin","gzip","oversize","utf8-invalid","plain","html","identity")) {
   var entity=mock(HttpEntity.class);when(entity.getContentType()).thenReturn(switch(test){case "null-type"->null;case "json"->"application/json";case "latin"->"text/plain; charset=iso-8859-1";case "html"->"text/html; charset=utf-8; x=y";default->"text/plain";});
   when(entity.getContentEncoding()).thenReturn(test.equals("gzip")?"gzip":test.equals("identity")?"IDENTITY":null);
   byte[] bytes=test.equals("oversize")?new byte[1048577]:test.equals("utf8-invalid")?new byte[]{(byte)0xff}:"body".getBytes(StandardCharsets.UTF_8);when(entity.getContent()).thenReturn(new ByteArrayInputStream(bytes));
   var response=mock(ClassicHttpResponse.class);when(response.getCode()).thenReturn(test.equals("status")?302:200);when(response.getEntity()).thenReturn(test.equals("empty")?null:entity);
   var client=mock(CloseableHttpClient.class);doAnswer(inv->((HttpClientResponseHandler<?>)inv.getArgument(1)).handleResponse(response)).when(client).execute(any(HttpGet.class),any(HttpClientResponseHandler.class));
   var builder=mock(HttpClientBuilder.class,RETURNS_SELF);when(builder.build()).thenReturn(client);
   try(var factory=mockStatic(HttpClients.class)) {factory.when(HttpClients::custom).thenReturn(builder);var fetcher=new PublicWebFetcher();try {
    if(Set.of("plain","html","identity").contains(test)) assertThat(((PublicWebFetcher.Page)method.invoke(fetcher,uri,new HttpGet(uri))).content()).isEqualTo("body");
    else assertThatThrownBy(()->method.invoke(fetcher,uri,new HttpGet(uri))).as(test).isInstanceOf(InvocationTargetException.class).hasCauseInstanceOf(BusinessException.class);
   }finally{fetcher.close();}}
  }
 }
 @Test void pinnedResolverRejectsHostChangesAndReturnsIndependentAddresses() throws Exception {
  var method=PublicWebFetcher.class.getDeclaredMethod("fetchBound",URI.class,HttpGet.class);method.setAccessible(true);var uri=URI.create("https://8.8.8.8/");
  var builder=mock(HttpClientBuilder.class,RETURNS_SELF);var client=mock(CloseableHttpClient.class);when(builder.build()).thenReturn(client);
  var connections=mock(org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.class,RETURNS_SELF);
  try(var factory=mockStatic(HttpClients.class);var managers=mockStatic(org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.class)){
   factory.when(HttpClients::custom).thenReturn(builder);managers.when(org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder::create).thenReturn(connections);
   var fetcher=new PublicWebFetcher();try{method.invoke(fetcher,uri,new HttpGet(uri));}finally{fetcher.close();}
   var captor=org.mockito.ArgumentCaptor.forClass(org.apache.hc.client5.http.DnsResolver.class);verify(connections).setDnsResolver(captor.capture());var resolver=captor.getValue();
   var first=resolver.resolve("8.8.8.8");assertThat(first).hasSize(1);first[0]=null;assertThat(resolver.resolve("8.8.8.8")[0]).isNotNull();assertThat(resolver.resolveCanonicalHostname("8.8.8.8")).isEqualTo("8.8.8.8");
   assertThatThrownBy(()->resolver.resolve("elsewhere.test")).isInstanceOf(UnknownHostException.class);assertThatThrownBy(()->resolver.resolveCanonicalHostname("elsewhere.test")).isInstanceOf(UnknownHostException.class);
  }
  var address=mock(InetAddress.class);when(address.getAddress()).thenReturn(new byte[0]);assertThat(PublicWebFetcher.publicAddress(address)).isFalse();
  assertThat((String)org.springframework.test.util.ReflectionTestUtils.invokeMethod(PublicWebFetcher.class,"canonicalPath",(String)null)).isEqualTo("/");
 }
}
