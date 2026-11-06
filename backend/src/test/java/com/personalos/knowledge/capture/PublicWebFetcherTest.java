package com.personalos.knowledge.capture;
import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import com.personalos.common.json.JsonCodec;
import org.junit.jupiter.api.Test;
class PublicWebFetcherTest {
 private final JsonCodec json=new JsonCodec(new tools.jackson.databind.ObjectMapper());
 record UrlFixture(String raw,String canonical){}
 record HtmlFixture(String name,String html,String title,List<String> contains,List<String> excludes){}
 @Test void sharedCanonicalPathFixtures() throws Exception {
  var fixtures=json.fromJson(Files.readString(Path.of("..","specs","035-safe-automation","fixtures","capture-urls.json")),UrlFixture[].class);
  for(var fixture:fixtures) {
   String canonical=PublicWebFetcher.canonical(fixture.raw()).toString();
   assertThat(canonical).as(fixture.raw()).isEqualTo(fixture.canonical());
   assertThat(PublicWebFetcher.canonical(canonical).toString()).as("规范化幂等："+fixture.raw()).isEqualTo(canonical);
  }
 }
 @Test void sharedHiddenHtmlFixtures() throws Exception {
  var fixtures=json.fromJson(Files.readString(Path.of("..","specs","035-safe-automation","fixtures","capture-html.json")),HtmlFixture[].class);
  for(var fixture:fixtures) {
   var page=PublicWebFetcher.extract("https://example.com/",fixture.html(),true);
   assertThat(page.title()).as(fixture.name()).isEqualTo(fixture.title());
   for(String text:fixture.contains())assertThat(page.content()).as(fixture.name()).contains(text);
   for(String text:fixture.excludes())assertThat(page.content()).as(fixture.name()).doesNotContain(text);
  }
 }
 @Test void rejectsPrivateSpecialAndMappedAddresses() throws Exception {
  for(String address:List.of("0.0.0.0","10.0.0.1","100.64.0.1","127.0.0.1","169.254.169.254","172.16.0.1","192.168.1.1","192.0.2.1","198.18.0.1","198.51.100.1","203.0.113.1","224.1.1.1","255.255.255.255","::1","fc00::1","fe80::1","2001:db8::1","2002:0808:0808::1","3fff::1"))assertThat(PublicWebFetcher.publicAddress(InetAddress.getByName(address))).as(address).isFalse();
  for(String address:List.of("8.8.8.8","1.1.1.1","2606:4700:4700::1111"))assertThat(PublicWebFetcher.publicAddress(InetAddress.getByName(address))).isTrue();
 }
 @Test void canonicalizesAndRefusesDangerousURLs() {
  assertThat(PublicWebFetcher.canonical("HTTPS://Example.COM:443/a/../b#frag").toString()).isEqualTo("https://example.com/b");
  for(String url:List.of("file:///etc/passwd","http://localhost/","https://x.internal/","http://user:pass@example.com/","https://example.com:444/","http://2130706433/","http://[::1]/","http://example.com/\n"))assertThatThrownBy(()->PublicWebFetcher.canonical(url)).isInstanceOf(RuntimeException.class);
  var fetcher=new PublicWebFetcher();try{assertThatThrownBy(()->fetcher.fetch("http://127.0.0.1/")).hasMessageContaining("禁止");}finally{fetcher.close();}
 }
 @Test void extractsPlainTextWithoutScriptsAndBounds() throws Exception {
  var page=PublicWebFetcher.extract("https://example.com/","<html><head><title>A &amp; B</title><style>secret-style</style></head><body><h1>标题</h1><script>secret-script()</script><p>Hello &amp; world</p></body></html>",true);
  assertThat(page.title()).isEqualTo("A & B");assertThat(page.content()).contains("标题","Hello & world").doesNotContain("secret");
  assertThatThrownBy(()->PublicWebFetcher.extract("https://example.com/","a".repeat(1048577),false)).isInstanceOf(RuntimeException.class);
 }
}
