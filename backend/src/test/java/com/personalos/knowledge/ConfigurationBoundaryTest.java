package com.personalos.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.calendar.config.CalendarProperties;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConfigurationBoundaryTest {
 @Test void knowledgeClampsOutOfRangeInputsAndRetainsValidOverrides(){
  for(double threshold:new double[]{-1,0,0.5,1,2})for(double weight:new double[]{-1,0,0.5,1,2})for(int n:new int[]{-1,0,1,21}){
   var p=new KnowledgeProperties(n,n,threshold,n,n==0?null:n<0?List.of():List.of("TXT"),n,n,n,true,n,weight);
   assertThat(p.embeddingDimensions()).isPositive();assertThat(p.topK()).isBetween(1,20);assertThat(p.similarityThreshold()).isBetween(0d,1d);assertThat(p.keywordChannelWeight()).isBetween(0.01d,1d);assertThat(p.chunkOverlapChars()).isGreaterThanOrEqualTo(0);assertThat(p.maxFileSizeBytes()).isPositive();
   assertThat(p.isExtensionAllowed(null)).isFalse();assertThat(p.isExtensionAllowed(" ")).isFalse();assertThat(p.isExtensionAllowed(" TXT ")).isTrue();assertThat(p.isExtensionAllowed("exe")).isFalse();
   assertThat(p.normalizeTopK(null)).isEqualTo(p.topK());assertThat(p.normalizeTopK(-1)).isEqualTo(p.topK());assertThat(p.normalizeTopK(99)).isEqualTo(20);
  }
 }
 @Test void calendarSafetyDefaultsCoverReminderLeadAndLimits(){
  for(Duration d:Arrays.asList(null,Duration.ZERO,Duration.ofSeconds(-1),Duration.ofMinutes(5),Duration.ofDays(8)))for(int n:new int[]{-1,0,1,501}){
   var p=new CalendarProperties(true,n,n,d,d,d,n,n,n);assertThat(p.scanIntervalMs()).isPositive();assertThat(p.scanInitialDelayMs()).isGreaterThanOrEqualTo(0);assertThat(p.scanLookahead()).isPositive();assertThat(p.scanLookback()).isPositive();assertThat(p.defaultReminderLead()).isGreaterThanOrEqualTo(Duration.ZERO);assertThat(p.maxQueryLimit()).isBetween(1,500);assertThat(p.effectiveScanLookahead()).isGreaterThanOrEqualTo(Duration.ofDays(7));
   assertThat(p.normalizeRangeDays(null)).isEqualTo(Math.min(7,p.maxRangeDays()));assertThat(p.normalizeRangeDays(-1)).isEqualTo(Math.min(7,p.maxRangeDays()));assertThat(p.normalizeRangeDays(999)).isEqualTo(p.maxRangeDays());assertThat(p.normalizeLimit(null)).isEqualTo(p.defaultQueryLimit());assertThat(p.normalizeLimit(-1)).isEqualTo(p.defaultQueryLimit());assertThat(p.normalizeLimit(999)).isEqualTo(p.maxQueryLimit());
  }
 }
}
