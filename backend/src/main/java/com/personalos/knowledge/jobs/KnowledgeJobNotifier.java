package com.personalos.knowledge.jobs;

import java.util.UUID;

/** 入库已提交后的尽力通知；通知失败时由数据库轮询恢复。 */
public interface KnowledgeJobNotifier { void submitted(UUID id); }
