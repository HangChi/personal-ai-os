package com.personalos.common.document;
import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
class PlainTextBoundaryTest {
 @Test void recognizesEverySupportedMimeAndExtensionAndHandlesMissingMetadata() throws Exception{
  var extractor=new PlainTextExtractor();byte[] bytes="text".getBytes(StandardCharsets.UTF_8);
  assertThat(extractor.extract(null,null,null)).isEmpty();assertThat(extractor.extract(bytes,null,null)).isEmpty();
  for(String mime:List.of("application/json","application/xml","application/yaml","application/x-yaml"))assertThat(extractor.extract(bytes,null,mime)).hasValue(new PlainTextExtractor.Extracted("text",false));
  for(String ext:List.of("md","txt","json","csv","log","yaml","yml","xml"))assertThat(extractor.extract(bytes,"NOTE."+ext.toUpperCase(Locale.ROOT),null)).isPresent();
  try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()){
   doc.createParagraph().createRun().setText("first");doc.createParagraph().createRun().setText("second");doc.write(out);
   for(String name:Arrays.asList("note.docx",null)){
    var result=extractor.extract(out.toByteArray(),name,name==null?"application/vnd.openxmlformats-officedocument.wordprocessingml.document":null);
    assertThat(result).isPresent();assertThat(result.orElseThrow().text()).isEqualTo("first\nsecond");
   }
  }
  assertThat(extractor.extract(bytes,"bad.docx",null)).isEmpty();assertThat(extractor.extract(bytes,"bad.pdf",null)).isEmpty();
 }
}
