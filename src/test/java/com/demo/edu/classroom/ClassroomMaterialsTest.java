package com.demo.edu.classroom;

import com.demo.chroma.doc.DocKnowledgeRegistry;
import com.demo.edu.mapper.ClassroomCourseMapper;
import com.demo.service.preprocessing.FileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 覆盖实际短中文资料、明确编码与传输边界；不改变公共 FileService 的识别行为。 */
class ClassroomMaterialsTest {
    final FileService files=mock(FileService.class);
    final ClassroomCourseService service=new ClassroomCourseService(mock(ClassroomCourseMapper.class),new ObjectMapper(),mock(ClassroomEngine.class),mock(ClassroomTutorModel.class),mock(ClassroomQuizAdapter.class),files,mock(DocKnowledgeRegistry.class),mock(TransactionTemplate.class),new ClassroomCourseServiceTest.ManualExecutor());
    final String actualText="菜园观察记录：菜地长6米、宽4米，面积24平方米。长方形面积等于长乘宽，6×4＝24平方米。\n";

    @Test void shortChineseUtf8WithoutBomBypassesMagicAndPreservesChineseFilename() {
        var result=service.materials(actualText.getBytes(StandardCharsets.UTF_8),"菜园观察资料.txt");
        assertEquals(actualText,result.path("text").asText());assertEquals("菜园观察资料.txt",result.path("fileName").asText());assertFalse(result.path("truncated").asBoolean());
        verifyNoInteractions(files);
    }
    @Test void markdownKeepsFormattingAndUtf8BomIsRemoved() {
        String markdown="# 菜园观察\n\n- 长：6米\n- 宽：4米\n\t面积 = 6 × 4 = 24 平方米。\r\n";
        var plain=service.materials(markdown.getBytes(StandardCharsets.UTF_8),"观察笔记.MD");assertEquals(markdown,plain.path("text").asText());
        var bom=service.materials(withBom(markdown,StandardCharsets.UTF_8,new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf}),"观察笔记.md");assertEquals(markdown,bom.path("text").asText());
        verifyNoInteractions(files);
    }
    @Test void utf16RequiresBomAndBothByteOrdersDecodeStrictly() {
        assertEquals(actualText,service.materials(withBom(actualText,StandardCharsets.UTF_16LE,new byte[]{(byte)0xff,(byte)0xfe}),"观察.txt").path("text").asText());
        assertEquals(actualText,service.materials(withBom(actualText,StandardCharsets.UTF_16BE,new byte[]{(byte)0xfe,(byte)0xff}),"观察.md").path("text").asText());
        var oddLength=new byte[]{(byte)0xff,(byte)0xfe,0x2d};
        assertTrue(assertThrows(ClassroomException.class,()->service.materials(oddLength,"损坏.txt")).getMessage().contains("编码"));
        verifyNoInteractions(files);
    }
    @Test void malformedUtf8AndUnmarkedGbkAreNotGuessed() {
        for(byte[] bytes:new byte[][]{new byte[]{(byte)0xc3,0x28},"中文资料".getBytes(Charset.forName("GBK")),new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf,(byte)0xe4,(byte)0xb8}}) {
            var error=assertThrows(ClassroomException.class,()->service.materials(bytes,"旧编码.txt"));assertEquals(422,error.status());assertTrue(error.getMessage().contains("UTF-8"));
        }
        verifyNoInteractions(files);
    }
    @Test void nulAndBinaryControlsAreRejectedEvenWhenEncodingIsValid() {
        for(String text:new String[]{"中文\u0000资料","中文\u0001资料","中文\u001b资料","中文\u007f资料","中文\u0085资料"}) {
            var error=assertThrows(ClassroomException.class,()->service.materials(text.getBytes(StandardCharsets.UTF_8),"二进制伪装.md"));assertEquals(422,error.status());assertTrue(error.getMessage().contains("控制字符"));
        }
        assertThrows(ClassroomException.class,()->service.materials("hello".getBytes(StandardCharsets.UTF_16LE),"无BOM.txt"));
        verifyNoInteractions(files);
    }
    @Test void exactCharacterBoundaryAndWholeFileValidationKeepTruncatedAccurate() {
        String exact="田".repeat(20000);
        var full=service.materials(exact.getBytes(StandardCharsets.UTF_8),"边界.txt");assertEquals(exact,full.path("text").asText());assertFalse(full.path("truncated").asBoolean());
        var cut=service.materials((exact+"园").getBytes(StandardCharsets.UTF_8),"长资料.md");assertEquals(exact,cut.path("text").asText());assertTrue(cut.path("truncated").asBoolean());
        // 正文末尾被截断也不能跳过编码与二进制检查，否则伪装文件可绕过纯文本校验。
        assertThrows(ClassroomException.class,()->service.materials((exact+"\u0000").getBytes(StandardCharsets.UTF_8),"长资料.txt"));
        var emoji=service.materials(("田".repeat(19999)+"🌾末尾").getBytes(StandardCharsets.UTF_8),"稻田.txt");assertEquals(19999,emoji.path("text").asText().length());assertTrue(emoji.path("truncated").asBoolean());
        verifyNoInteractions(files);
    }
    @Test void tenMbBoundaryEmptyAndWhitespaceAreCheckedBeforeExtraction() {
        byte[] maximum=new byte[10*1024*1024];Arrays.fill(maximum,(byte)'a');
        var result=service.materials(maximum,"大资料.txt");assertEquals(20000,result.path("text").asText().length());assertTrue(result.path("truncated").asBoolean());
        assertThrows(ClassroomException.class,()->service.materials(new byte[maximum.length+1],"超限.txt"));
        assertThrows(ClassroomException.class,()->service.materials(new byte[0],"空.txt"));
        assertThrows(ClassroomException.class,()->service.materials(null,"空.txt"));
        assertEquals(422,assertThrows(ClassroomException.class,()->service.materials(" \r\n\t".getBytes(StandardCharsets.UTF_8),"空白.md")).status());
        assertEquals(422,assertThrows(ClassroomException.class,()->service.materials(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf},"只有BOM.txt")).status());
        verifyNoInteractions(files);
    }
    @Test void pdfAndWordKeepExistingExtractorAndItsTruncationContract() {
        byte[] bytes={1,2,3};when(files.extractContent(same(bytes),eq(20001))).thenReturn("文".repeat(20001));
        for(String filename:new String[]{"参考.pdf","教材.docx","旧教材.doc"}) {
            var result=service.materials(bytes,filename);assertEquals(20000,result.path("text").asText().length());assertTrue(result.path("truncated").asBoolean());assertEquals(filename,result.path("fileName").asText());
        }
        verify(files,times(3)).extractContent(same(bytes),eq(20001));
    }
    private byte[] withBom(String text,Charset charset,byte[] bom) {
        byte[] body=text.getBytes(charset);byte[] bytes=new byte[bom.length+body.length];System.arraycopy(bom,0,bytes,0,bom.length);System.arraycopy(body,0,bytes,bom.length,body.length);return bytes;
    }
}
