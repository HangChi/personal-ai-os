package com.personalos.task.dto;

import java.time.Instant;
import java.util.UUID;

/** 项目可归档但不物理删除，保留任务历史。 */
public record ProjectDto(UUID id,String name,String description,boolean archived,Instant createdAt,Instant updatedAt) {}
