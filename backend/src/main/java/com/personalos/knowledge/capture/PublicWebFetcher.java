package com.personalos.knowledge.capture;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.text.*;
import javax.swing.text.html.*;
import javax.swing.text.html.parser.ParserDelegator;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/** 所有 DNS 地址先分类，再绑定同一组连接地址；不继承代理、Cookie、凭据或重定向。 */
@Component
public class PublicWebFetcher {
    public record Page(String url,String title,String content) {}
    static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED,message); }
    public static URI canonical(String raw) {
        try {
            if(raw==null || raw.length()>2048 || raw.chars().anyMatch(c->Character.isISOControl(c)||c>127)) throw invalid("URL 无效或超过 2048 字符");
            URI u=new URI(raw.strip()).normalize(); String scheme=u.getScheme()==null?"":u.getScheme().toLowerCase(Locale.ROOT);
            String host=u.getHost(); int port=u.getPort();
            if(!Set.of("http","https").contains(scheme)||host==null||u.getRawUserInfo()!=null||port!=-1&&port!=(scheme.equals("https")?443:80)) throw invalid("仅支持无凭据、标准端口的公开 HTTP(S) URL");
            host=host.toLowerCase(Locale.ROOT); if(host.endsWith(".")) host=host.substring(0,host.length()-1);
            if(host.equals("localhost")||host.endsWith(".localhost")||host.endsWith(".local")||host.endsWith(".internal")||!host.contains(".")||host.contains(":")||host.contains("[")) throw invalid("禁止本机或内部地址");
            String path=u.getRawPath(); if(path==null||path.isEmpty())path="/";
            return URI.create(scheme+"://"+host+path+(u.getRawQuery()==null?"":"?"+u.getRawQuery()));
        } catch(URISyntaxException|IllegalArgumentException ex) { throw invalid("URL 格式不正确"); }
    }
    public static boolean publicAddress(InetAddress address) {
        byte[] b=address.getAddress();
        // 只允许 IPv4 公网及 IPv6 2000::/3；拒绝所有映射、隧道和保留范围。
        if(b.length==4) {
            int a=b[0]&255,c=b[1]&255,d=b[2]&255;
            return !(a==0||a==10||a==127||a>=224||a==100&&c>=64&&c<=127||a==169&&c==254||a==172&&c>=16&&c<=31||a==192&&(c==0||c==168||c==2||c==88&&d==99)||a==198&&(c==18||c==19||c==51&&d==100)||a==203&&c==0&&d==113);
        }
        if(b.length!=16 || (b[0]&0xe0)!=0x20) return false;
        return !((b[0]&255)==0x20&&(b[1]&255)==1 && ((b[2]&255)<2 || (b[2]&255)==0x0d&&(b[3]&255)==0xb8)) && !((b[0]&255)==0x20&&(b[1]&255)==2) && !((b[0]&255)==0x3f&&(b[1]&255)==0xff&&(b[2]&0xf0)==0);
    }
    private final ExecutorService requests=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"public-web-capture");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    @jakarta.annotation.PreDestroy public void close() { requests.shutdownNow(); }
    public Page fetch(String raw) {
        URI uri=canonical(raw);
        HttpGet request=new HttpGet(uri);
        try {
            Future<Page> future=requests.submit(()->fetchBound(uri,request));
            try { return future.get(15,TimeUnit.SECONDS); }
            catch(TimeoutException ex) { request.cancel();future.cancel(true);throw invalid("网页采集超时（15秒）"); }
            catch(ExecutionException ex) { if(ex.getCause() instanceof BusinessException b)throw b;throw invalid("网页采集失败，请检查公开地址或稍后重试"); }
            catch(InterruptedException ex) { request.cancel();future.cancel(true);Thread.currentThread().interrupt();throw invalid("网页采集已取消"); }
        } catch(RejectedExecutionException ex) { throw invalid("采集任务繁忙，请稍后再试"); }
    }
    private Page fetchBound(URI uri,HttpGet request) throws Exception {
        InetAddress[] addresses=InetAddress.getAllByName(uri.getHost());
        if(addresses.length==0||Arrays.stream(addresses).anyMatch(a->!publicAddress(a))) throw invalid("禁止访问私网、保留或混合 DNS 地址");
        var resolver=new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException { if(!host.equalsIgnoreCase(uri.getHost())) throw new UnknownHostException(); return addresses.clone(); }
            public String resolveCanonicalHostname(String host) throws UnknownHostException { if(!host.equalsIgnoreCase(uri.getHost()))throw new UnknownHostException();return host; }
        };
        var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).build();
        var config=RequestConfig.custom().setConnectTimeout(Timeout.ofSeconds(4)).setResponseTimeout(Timeout.ofSeconds(5)).setConnectionRequestTimeout(Timeout.ofSeconds(1)).setAuthenticationEnabled(false).setContentCompressionEnabled(false).build();
        try(var client=HttpClients.custom().setConnectionManager(manager).setDefaultRequestConfig(config).disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableContentCompression().build()) {
            request.setHeader("User-Agent","Personal-AI-OS-Capture/1.0");request.setHeader("Accept","text/html, text/plain");
            return client.execute(request,response->{
                if(response.getCode()!=200)throw invalid("网页响应非200，重定向不会自动跟随");
                var entity=response.getEntity();if(entity==null)throw invalid("网页正文为空");
                String type=entity.getContentType()==null?"":entity.getContentType().toLowerCase(Locale.ROOT);
                String media=type.split(";",2)[0].strip();
                if(!Set.of("text/html","text/plain").contains(media) || type.contains("charset=")&&!type.matches(".*charset=\"?utf-8\"?(;.*)?")) throw invalid("只支持 UTF-8 HTML 或纯文本网页");
                if(entity.getContentEncoding()!=null&&!entity.getContentEncoding().equalsIgnoreCase("identity"))throw invalid("不支持压缩响应");
                byte[] bytes;try(var in=entity.getContent()) {bytes=in.readNBytes(1_048_577);}
                if(bytes.length>1_048_576)throw invalid("网页正文超过 1MiB");
                String text;try {text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException ex){throw invalid("网页不是有效 UTF-8");}
                return extract(uri.toString(),text,media.equals("text/html"));
            });
        }
    }
    public static Page extract(String url,String source,boolean html) throws IOException {
        StringBuilder body=new StringBuilder(),title=new StringBuilder();
        if(html) new ParserDelegator().parse(new StringReader(source),new HTMLEditorKit.ParserCallback() {
            int blocked;boolean inTitle;
            public void handleStartTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {String t=tag.toString();if(Set.of("script","style","noscript","iframe","object","template").contains(t))blocked++;if(tag==HTML.Tag.TITLE)inTitle=true;if(blocked==0&&tag.isBlock())body.append('\n');}
            public void handleEndTag(HTML.Tag tag,int pos) {if(Set.of("script","style","noscript","iframe","object","template").contains(tag.toString())&&blocked>0)blocked--;if(tag==HTML.Tag.TITLE)inTitle=false;if(blocked==0&&tag.isBlock())body.append('\n');}
            public void handleText(char[] data,int pos) {if(blocked==0){if(inTitle)title.append(data);else body.append(data).append(' ');}}
            public void handleSimpleTag(HTML.Tag tag,MutableAttributeSet attrs,int pos) {if(blocked==0&&tag==HTML.Tag.BR)body.append('\n');}
        },true); else body.append(source);
        String text=body.toString().replace('\u0000',' ').replaceAll("[\\t\\x0B\\f ]+"," ").replaceAll(" *\\n *","\n").replaceAll("\\n{3,}","\n\n").strip();
        if(text.isEmpty()||text.getBytes(StandardCharsets.UTF_8).length>1_048_576)throw invalid("提取正文为空或超过1MiB");
        String name=title.toString().replaceAll("\\s+"," ").strip();if(name.isEmpty())name=URI.create(url).getHost();if(name.length()>200)name=name.substring(0,200);
        return new Page(url,name,text);
    }
}
