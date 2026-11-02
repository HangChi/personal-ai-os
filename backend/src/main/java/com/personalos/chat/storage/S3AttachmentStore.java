package com.personalos.chat.storage;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import org.springframework.core.io.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** 私有对象经应用认证代理读取；键由服务端 UUID 生成，不使用用户文件名。 */
public final class S3AttachmentStore implements AttachmentObjectStore {
    private final S3Client client;
    private final String bucket;
    private final String prefix;
    private final String owner;
    private final boolean cleanup;
    private final long maximum;
    private String cursor;
    public S3AttachmentStore(S3Client client,String bucket,String namespace,boolean cleanup,long maximum) {
        if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || namespace==null || namespace.isBlank())
            throw new IllegalArgumentException("S3 bucket 与 namespace 必须合法且非空");
        this.client=client; this.bucket=bucket; this.owner=JsonCodec.sha256Hex(namespace);
        this.prefix="personalos/"+owner+"/attachments/"; this.cleanup=cleanup; this.maximum=maximum;
    }
    private String key(String id) { java.util.UUID.fromString(id); return prefix+id; }
    @Override public void put(String id,byte[] bytes,String contentType) {
        if (bytes.length>maximum) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"附件超过大小上限");
        try { client.putObject(PutObjectRequest.builder().bucket(bucket).key(key(id)).contentType(contentType)
                .metadata(Map.of("personalos-owner",owner)).build(),RequestBody.fromBytes(bytes)); }
        catch(RuntimeException ex) { throw unavailable(); }
    }
    private HeadObjectResponse head(String id) {
        try { return client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key(id)).build()); }
        catch(S3Exception ex) { if (ex.statusCode()==404) return null; throw unavailable(); }
        catch(RuntimeException ex) { throw unavailable(); }
    }
    @Override public Long size(String id) {
        var head=head(id);
        if (head==null || !owner.equals(head.metadata().get("personalos-owner"))) return null;
        if (head.contentLength()>maximum) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"对象超出附件大小上限");
        return head.contentLength();
    }
    @Override public Resource load(String id) {
        return new AbstractResource() {
            @Override public String getDescription() { return "附件对象 "+id; }
            @Override public String getFilename() { return id; }
            @Override public long contentLength() { Long length=size(id); if(length==null) throw missing(); return length; }
            @Override public InputStream getInputStream() {
                if(size(id)==null) throw missing();
                try { return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key(id)).build()); }
                catch(RuntimeException ex) { throw unavailable(); }
            }
        };
    }
    @Override public void delete(String id) {
        var head=head(id);
        if (head!=null && owner.equals(head.metadata().get("personalos-owner")))
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(id)).build());
    }
    @Override public int prune(Set<String> referenced,Instant before) {
        if (!cleanup) return 0;
        int removed=0;
        // 每轮有界扫描；只清理当前命名空间且带本应用 owner 标记的对象。
        var objects=client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(cursor).maxKeys(100).build());
        cursor=objects.nextContinuationToken();
        for(var object:objects.contents()) {
            String id=object.key().substring(prefix.length());
            if (!id.matches("[0-9a-fA-F-]{36}") || referenced.contains(id) || !object.lastModified().isBefore(before)) continue;
            if (size(id)!=null) { delete(id); removed++; }
        }
        return removed;
    }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.INTERNAL_ERROR,"附件对象存储暂不可用，请稍后重试"); }
    private BusinessException missing() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"附件不存在"); }
}
