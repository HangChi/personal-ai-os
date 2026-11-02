package com.personalos.knowledge.governance;
import java.time.LocalDate;
import java.util.UUID;
public record SourceInfo(UUID spaceId,String spaceName,String version,String scope,LocalDate effectiveFrom,
                         LocalDate effectiveUntil,String authority,String lifecycle,long revision,String reviewState) { }
