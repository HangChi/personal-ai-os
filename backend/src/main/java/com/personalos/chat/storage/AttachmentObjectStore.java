package com.personalos.chat.storage;

import java.time.Instant;
import java.util.Set;
import org.springframework.core.io.Resource;

public interface AttachmentObjectStore {
    void put(String id,byte[] bytes,String contentType);
    Long size(String id);
    Resource load(String id);
    void delete(String id);
    int prune(Set<String> referenced,Instant before);
}
