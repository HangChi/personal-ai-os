package com.personalos.knowledge.splitter;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownBoundaryTest {
 @Test void nullAndWhitespaceHaveNoChunks(){
  var s=new MarkdownStructureTextSplitter(0,0,-1);
  assertThat(s.splitText(null)).isEmpty();
  assertThat(s.splitText(" \n")).isEmpty();
  assertThat(s.splitText("甲乙丙")).containsExactly("甲","乙","丙");
 }
 @Test void allNaturalBreaksPreserveTheSentenceAndTheWholePayload(){
  for(String delimiter:List.of("。","！","？","；","\n","."," ","\t")){
   String source="甲".repeat(8)+delimiter+"乙".repeat(10);
   var blocks=new MarkdownStructureTextSplitter(10,1,0).splitText(source);
   assertThat(blocks.getFirst()).isEqualTo(("甲".repeat(8)+delimiter).strip());
   assertThat(String.join("",blocks).replaceAll("\\s","")).isEqualTo(source.replaceAll("\\s",""));
  }
 }
 @Test void longHeadingAndFullPreviousChunkOverlapAreBoundedAndRetained(){
  var blocks=new MarkdownStructureTextSplitter(10,1,30).splitText("甲乙\n\n"+"丙".repeat(21));
  assertThat(blocks.get(1)).startsWith("…甲乙\n");
  var titled=new MarkdownStructureTextSplitter(500,1,0).splitText("# "+"标题".repeat(100)+"\n正文");
  assertThat(titled).hasSize(1);
  assertThat(titled.getFirst()).startsWith("【章节："+"标题".repeat(60)+"…】\n");
 }

 @Test void emptySectionsAndTinyParagraphsNeverCreateBlankChunks(){
  for(String text:List.of("# Heading","# First"+System.lineSeparator()+"# Second",
    "before"+System.lineSeparator()+"# Heading","# H"+System.lineSeparator()+"   ",
    "abc"+System.lineSeparator().repeat(3)+"def","0123456789"+System.lineSeparator().repeat(2)+"xy")){
   for(int target:new int[]{1,8,10,20}) for(int overlap:new int[]{0,2}) {
    var chunks=new MarkdownStructureTextSplitter(target,1,overlap).splitText(text);
    assertThat(chunks).allMatch(c -> !c.isBlank());
   }
  }
 }
}
