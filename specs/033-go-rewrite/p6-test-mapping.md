# P6 原测试职责映射

固定 Java 基线：`cb5d694d62fa93742b5789b97273e76d931ce335`。共 24 类、115 个测试声明。下表逐条保留原方法名和 Go 断言；Spring/Mockito 实现细节以 Go 的事务/生命周期验证替代。

复验：`python3 scripts/migration/go_p6_progress.py --mapping`。实现和实测边界见 [P6 验收](p6-completion.md)。跨模块导入/导出与发布恢复仍归 P7/P8；不改变五个发布门禁。

## AdvancedRagIntegrationTest

启用实际管线、双源证据和确定作答保护。

对应 Go：`TestFoundationP6SecurityGovernanceAndGuardedChat`、`TestFoundationP6RealFacilitiesRolesAndCacheFailure`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `enabledApplicationWiresPipelineAndProtectsTwoKnowledgeSourcesEndToEnd` | 48 |

## CrossEncoderRerankerTest

Cohere/DashScope协议、非法/重复/越界排序拒收。

对应 Go：`TestCrossEncoderCohereDashscopeAndInvalidRanks`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `supportsNativeDashScopePayloadAndResults` | 11 |
| `ranksByValidatedIndicesAndKeepsVectorScoresSeparate` | 29 |

## DocumentFilenameSupportTest

10声明：空名/路径/300 UTF16/扩展名/标题/图片及文档MIME。

对应 Go：`TestFilenameAndValidation`、`TestJavaP6FilenameKeywordSplitterAndCitationOracle`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `handlesBlankNames` | 18 |
| `stripsPathComponents` | 26 |
| `truncatesButKeepsExtension` | 35 |
| `truncatesWithoutExtension` | 46 |
| `ignoresOverlongExtension` | 55 |
| `truncatesTitle` | 70 |
| `imageMimeTypesAreExact` | 80 |
| `documentMimeTypes` | 90 |
| `extractsExtension` | 103 |
| `stripsExtensionForTitle` | 111 |

## DocumentWriteLockTest

多连接数据库租约替代本地锁、独立文档及幂等释放；共享协调包另验续租和事务完成释放。

对应 Go：`TestP6DimensionAndDocumentLeaseFencing`、`TestP6IndexJobClaimCompetitionExpiryAndFencing`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `rejectsSecondAcquire` | 23 |
| `canReacquireAfterRelease` | 37 |
| `differentDocumentsDoNotBlockEachOther` | 50 |
| `releaseIsIdempotent` | 63 |

## DocxDocumentReaderTest

段落/表格顺序、空白与损坏。

对应 Go：`TestDOCXParagraphsTablesAndSafety`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `extractsParagraphsAndTables` | 43 |
| `emptyDocumentIsRejected` | 60 |
| `brokenDocxIsTranslated` | 75 |

## EmbeddingDimensionMismatchIntegrationTest

维度不符零写入。

对应 Go：`TestP6DimensionAndDocumentLeaseFencing`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `mismatchedEmbeddingDimensionIsRejectedWithZeroWrites` | 79 |

## HybridSearchIntegrationTest

全量统计先于limit、稀有词、空score、双通道、中文与去重；matchedBy经序列化和HTTP保留。

对应 Go：`TestP6HybridThresholdIDFChineseAndLiteralPattern`、`TestP6KeywordWindowCountsBeforeLimitAndRRF`、`TestP6LiveCorpusControlledJavaRecallAndCitationParity`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `batchedKeywordStatisticsAreComputedBeforeLimit` | 118 |
| `keywordChannelRescuesRareToken` | 144 |
| `keywordOnlyHitHasNullScore` | 159 |
| `bothChannelsRankFirst` | 174 |
| `chineseOnlyQueryFallsBackToVector` | 187 |
| `dedupeAfterFusion` | 197 |
| `apiExposesMatchedBy` | 205 |

## ImageDocumentReaderTest

MIME、无文本/失败、无工具多模态协议。

对应 Go：`TestVisionNormalizationMIMEAndErrors`、`TestGatewayFencesOversizedJSONBudgetAndVisionPlainClient`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `extractsTextViaVisionModel` | 26 |
| `emptyVisionResultIsRejected` | 44 |
| `blankVisionResultIsRejected` | 54 |

## KeywordExtractorTest

9声明与152条原有查询逐项对照。

对应 Go：`TestKeywordsIdentifierStopWordsOrderAndLimit`、`TestJavaP6FilenameKeywordSplitterAndCitationOracle`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `shouldExtractIdentifiers` | 17 |
| `shouldKeepIdentifierPunctuation` | 31 |
| `shouldDropStopWords` | 40 |
| `chineseOnlyQueryYieldsNothing` | 49 |
| `singleLetterAndPureNumberAreNotTokens` | 56 |
| `numericLeadingIdentifierKeptWhole` | 64 |
| `shouldCapTokenCount` | 71 |
| `shouldDedupeAndKeepOrder` | 80 |
| `shouldHandleBlankInput` | 86 |

## KnowledgeDocumentPageOverflowIntegrationTest

巨大页号、带keyword与正常分页。

对应 Go：`TestP6DocumentAtomicReplaceMetadataDeleteAndPagination`、`TestFoundationP6DocumentsAPIAndRolePolling`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `hugePageReturnsEmptyPageNotServerError` | 97 |
| `hugePageWithKeywordAlsoSafe` | 116 |
| `largeButValidPageStillWorks` | 130 |

## KnowledgeDocumentUpdateIntegrationTest

同ID/创建时间、失败保留旧代、新引用元数据、写锁及完整删除。

对应 Go：`TestP6DocumentAtomicReplaceMetadataDeleteAndPagination`、`TestP6DimensionAndDocumentLeaseFencing`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `updatesInPlace` | 97 |
| `clearsStaleVectors` | 116 |
| `refreshesVectorMetadata` | 133 |
| `truncatesOverlongFilename` | 153 |
| `rejectsConcurrentUpdate` | 171 |
| `deleteRespectsSameLock` | 195 |

## KnowledgeGovernanceIntegrationTest

范围/日期/审计、两方原文/人工决议/失效、已存冲突补齐、增量review与投影失败；其中backupRestores的跨模块导入导出职责明确归P7/T704，完整备份恢复归P8/T804。

对应 Go：`TestP6GovernanceSourceMetadataConflictAuditAndInvalidation`、`TestP6GovernanceRejectsForgedQuotesAndKeepBoth`、`TestP6ReviewIncrementalFailureRetryAndSourceRevision`、`TestP6ProjectionRevisionRecoveryDeleteAndReindex`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `sourceScopeVersionAndDateAreValidatedAndAuditable` | 46 |
| `conflictHasBothQuotesHumanDecisionAndRevisionInvalidation` | 54 |
| `existingOpenFindingExpandsOpposingSourceAndCannotBeMissedByNextModel` | 67 |
| `ingestionReviewIsIncrementalAndRetriableWithoutOverwritingKnowledge` | 73 |
| `backupRestoresKnowledgeSpaceAndEffectiveScope` | 93 |
| `projectionFailureRetainsOutboxAndConcurrentChangeCannotBeAcknowledged` | 107 |

## KnowledgeIndexJobsIntegrationTest

202先返回、回执原子提交、替换失败、失效重领、旧Worker拒写、三次上限及跨API容量。

对应 Go：`TestP6IndexJobIdempotencyAtomicResultAndPayloadCleanup`、`TestP6IndexJobClaimCompetitionExpiryAndFencing`、`TestP6QueueCapacityAcrossAPIReplicas`、`TestP6IndexSIGKILLAndProcessRestart`、`TestFoundationP6DocumentsAPIAndRolePolling`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `httpSubmissionReturnsAcceptedBeforeIndexingAndProgressSurvivesReconnect` | 52 |
| `queuedUploadCommitsDocumentVectorsAndReceiptTogetherAndIsIdempotent` | 73 |
| `replacementKeepsOldContentUntilNewIndexAndReceiptCommit` | 88 |
| `expiredClaimRecoversAndOldWorkerCannotWriteOrOverwriteReceipt` | 103 |
| `losingLeaseDuringEmbeddingFencesAllIndexWrites` | 116 |
| `vectorWriteFailureRollsBackDocumentAndRetriesAtMostThreeTimes` | 127 |
| `claimNeverReturnsSameTaskToTwoWorkers` | 140 |

## KnowledgeMinLengthIntegrationTest

短文拒收及等于最小阈值接收。

对应 Go：`TestFilenameAndValidation`、`TestP6DimensionAndDocumentLeaseFencing`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `shouldRejectTooShortDocument` | 74 |
| `shouldAcceptDocumentAtThreshold` | 91 |

## KnowledgeRagIntegrationTest

上传/检索/问答、引用过滤、空库不生成、删除/400/404、聊天共享检索与重复上传独立。

对应 Go：`TestFoundationP6DocumentsAPIAndRolePolling`、`TestFoundationP6SecurityGovernanceAndGuardedChat`、`TestP6LiveCorpusControlledJavaRecallAndCitationParity`、`TestQAActualCitationNumbersGuardAndEmptyNoAnswerCall`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `uploadIndexesDocumentWithConsistentDimension` | 144 |
| `retrievalRanksRelatedDocumentFirst` | 167 |
| `searchEndpointDoesNotInvokeModelAndCapsTopK` | 182 |
| `askReturnsAnswerWithTraceableCitations` | 198 |
| `citationsOnlyIncludeFragmentsTheAnswerUses` | 233 |
| `citationsHandleOutOfRangeAndMissingMarks` | 256 |
| `deletingDocumentRemovesOnlyItsOwnVectors` | 280 |
| `askOnEmptyKnowledgeBaseDoesNotCallModel` | 309 |
| `unsupportedInputsProduceZeroWrites` | 322 |
| `deletingUnknownDocumentReturns404` | 355 |
| `chatAgentCanSearchKnowledgeBase` | 366 |
| `duplicateUploadsAreIndependentDocuments` | 395 |

## MarkdownStructureTextSplitterTest

14声明的标题栈/同级/段落/前缀/重叠/空白/无丢失/围栏；15组固定Java切分逐项相等。

对应 Go：`TestMarkdownStructureAndNoLoss`、`TestMarkdownFenceOverlapMinimumAndLongPrefix`、`TestJavaP6FilenameKeywordSplitterAndCitationOracle`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `shouldAttachHeadingPath` | 25 |
| `siblingHeadingsShouldNotLeakIntoPath` | 50 |
| `shouldSplitByParagraphNotMidSentence` | 76 |
| `mergedBlocksShouldHaveSinglePrefix` | 91 |
| `plainTextWithoutHeadings` | 119 |
| `inlineHashIsNotHeading` | 130 |
| `overlapShouldPrefixPreviousTail` | 142 |
| `emptyInputProducesNothing` | 153 |
| `shouldNotLoseContent` | 160 |
| `realWorldDocumentKeepsNearlyAllText` | 190 |
| `hashInsideFenceIsNotHeading` | 227 |
| `fenceKeepsFollowingHeadingIntact` | 256 |
| `tildeFenceIsRecognized` | 280 |
| `headingAfterFenceStillWorks` | 299 |

## OpenSearchGatewayIntegrationTest

中文混合索引、版本拒旧及删除。

对应 Go：`TestP6RealOpenSearchProjectionHydrationVersionAndDelete`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `chineseHybridVersionFenceAndDeletion` | 9 |

## PdfDocumentReaderTest

真实文本PDF、空白/损坏和完整500页限制。

对应 Go：`TestPDFTextEmptyBrokenAndPageLimit`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `extractsTextLayer` | 55 |
| `emptyTextLayerIsRejected` | 67 |
| `brokenPdfIsTranslated` | 77 |

## RabbitJobNotifierIntegrationTest

durable/confirm、重复唤醒不消费业务任务、重连及轮询兜底。

对应 Go：`TestP6RealRabbitDurableConfirmedWakeupsAndReconnect`、`TestP6SignalConfirmedRevisionFailureAndBusinessIsolation`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `confirmsDurableIdNotificationsAndMissingRouteFallsBackWithoutLosingDatabaseWork` | 19 |
| `fanoutDeliversToTwoIndependentSubscribers` | 37 |

## RagModelGatewayTest

围栏JSON、64K上限、预算、超时取消及关闭。

对应 Go：`TestGatewayFencesOversizedJSONBudgetAndVisionPlainClient`、`TestGatewayBoundedQueueDeadlineCancellationAndClose`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `structuredCallAcceptsFencedJsonAndRejectsOversizedResponses` | 12 |
| `timeoutInterruptsAuxiliaryCallAndReturnsDegradableFailure` | 22 |

## RagRetrievalPipelineTest

七声明：冲突top1、非法重排回退、伪造摘录、改写救回、空库、全部阶段不可用及不同条件澄清。

对应 Go：`TestPipelineReadyConflictTopOneFallbackAndNoContent`、`TestPipelineRewriteRecoveryAndFailedStages`、`TestEvidenceReviewCoverageGroundingPeriodsAndClarification`、`TestP6GovernanceOutageRetainsEvidenceAndGuardsAnswer`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `conflictingSourcesSurviveTopOneAndAskForClarification` | 27 |
| `invalidRerankFallsBackWithoutLosingSources` | 39 |
| `fabricatedConflictQuoteIsRejectedAndGenerationIsGuarded` | 46 |
| `rewriteCanRecoverWhenOriginalQueryReturnsNothing` | 54 |
| `emptyLibraryDoesNotCallGeneration` | 63 |
| `unavailableStagesRetainOriginalAndRefuseToInventConclusion` | 69 |
| `disjointConditionsRequestClarificationWithoutInventingConflict` | 74 |

## RichDocumentIngestIntegrationTest

PDF文本/扫描拒收、DOCX表格及不支持格式零写入；标准库解析替换PDFBox/POI。

对应 Go：`TestPDFTextEmptyBrokenAndPageLimit`、`TestDOCXParagraphsTablesAndSafety`、`TestP6DocumentAtomicReplaceMetadataDeleteAndPagination`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `pdfIsIngestedAndRetrievable` | 101 |
| `scannedPdfIsRejectedWithoutWrites` | 117 |
| `docxTablesAreIndexed` | 132 |
| `unsupportedExtensionIsRejected` | 149 |

## VectorStoreIndexMigrationTest

原42迁移保留JSON/document_id表达式索引与HNSW；真实EXPLAIN采用索引，事务内去索引后Seq Scan反证并回滚恢复。

对应 Go：`TestP6IndependentUploadsAndExpressionIndexPlan`、`TestP6HybridThresholdIDFChineseAndLiteralPattern`、`TestP6DimensionAndDocumentLeaseFencing`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `documentIdExpressionIndexExists` | 67 |
| `metadataColumnIsJson` | 78 |
| `plannerUsesIndexOnlyWhenPresent` | 90 |

## VectorStoreInspectorPatternTest

原wordStartPattern仅为未被生产查询调用的辅助函数；生产searchByKeywords明确使用ILIKE子串，本迁移保留实际SQL与转义，不添加未使用正则API。

对应 Go：`TestP6HybridThresholdIDFChineseAndLiteralPattern`、`TestP6KeywordWindowCountsBeforeLimitAndRRF`。

| 原方法 | 固定来源行 |
| --- | ---: |
| `shouldAnchorAtWordStart` | 21 |
| `shouldEscapeRegexMetacharacters` | 28 |
| `numericLeadingToken` | 43 |
