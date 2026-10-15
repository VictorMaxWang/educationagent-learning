package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClassroomSlideSanitizerTest {
    final ObjectMapper json=new ObjectMapper();

    @Test void stripsHandlersFramesForeignObjectsAndJavascriptLinks() throws Exception {
        ObjectNode scene=(ObjectNode)json.readTree("""
          {"type":"slide","content":{"canvas":{"elements":[
          {"type":"text","content":"<p onclick='alert(1)'>正常文字<img src=x onerror='alert(2)'><svg><foreignObject><iframe srcdoc='evil'></iframe></foreignObject><path d='M0 0L1 1' onload='alert(3)'/></svg><a href='javascript:alert(1)'>坏链接</a></p>","link":{"type":"web","target":"javascript:alert(1)"}},
          {"type":"image","src":"data:image/svg+xml;base64,PHN2Zz4="},
          {"type":"shape","pattern":"javascript:alert(1)","text":{"content":"<script>alert(1)</script><b>标签</b>"}},
          {"type":"table","data":[[{"text":"<img src=x onerror='alert(1)'><b>单元格</b>"}]]}
          ]}}}
          """);
        ClassroomSlideSanitizer.cleanScene(scene);
        var elements=scene.path("content").path("canvas").path("elements");
        var text=elements.get(0).path("content").asText();
        var document=Jsoup.parseBodyFragment(text);
        assertEquals(0,document.select("[onclick],[onerror],[onload],script,iframe,foreignObject,a,img").size());
        assertTrue(document.text().contains("正常文字"));
        assertFalse(elements.get(0).has("link"));
        assertEquals("",elements.get(1).path("src").asText());
        assertEquals("",elements.get(2).path("pattern").asText());
        assertFalse(elements.get(2).path("text").path("content").asText().contains("script"));
        assertFalse(elements.get(3).path("data").get(0).get(0).path("text").asText().contains("onerror"));
    }

    @Test void keepsBasicTypographyAndFormulaSourceButDropsActiveCss() {
        String cleaned=ClassroomSlideSanitizer.cleanHtml("""
          <span data-inline-math="\\frac{1}{2}" style="font-size:24px;color:#123456;position:fixed;background-image:url(https://evil.example);width:expression(alert(1))">\\frac{1}{2}</span>
          <svg viewBox="0 0 100 20"><path d="M0 0L100 20" fill="url(https://evil.example/p.svg#x)"/></svg>
          """);
        var doc=Jsoup.parseBodyFragment(cleaned);
        var span=doc.selectFirst("span");
        assertNotNull(span); assertEquals("\\frac{1}{2}",span.attr("data-inline-math"));
        assertTrue(span.attr("style").contains("font-size:24px"));
        assertTrue(span.attr("style").contains("color:#123456"));
        assertFalse(span.attr("style").contains("fixed"));
        assertFalse(cleaned.contains("url(")); assertFalse(cleaned.contains("expression("));
        assertEquals("M0 0L100 20",doc.selectFirst("path").attr("d"));
    }

    @Test void preservesLatexAndDoesNotConfuseSandboxHtmlWithParentHtml() {
        ObjectNode slide=json.createObjectNode().put("type","slide");
        var element=slide.putObject("content").putObject("canvas").putArray("elements").addObject();
        element.put("type","latex").put("latex","\\frac{1}{2}").put("html","<span onclick='bad()'>½</span>");
        ClassroomSlideSanitizer.cleanScene(slide);
        assertEquals("\\frac{1}{2}",element.path("latex").asText()); assertFalse(element.path("html").asText().contains("onclick"));
        ObjectNode interactive=json.createObjectNode().put("type","interactive");
        interactive.putObject("content").put("html","<script>window.safeDemo=1</script>");
        ClassroomSlideSanitizer.cleanScene(interactive);
        assertTrue(interactive.path("content").path("html").asText().contains("script"),"互动脚本仅进入iframe沙箱，不能被当作主页面富文本使用");
    }

    @Test void urlPolicyRejectsActiveAndAmbiguousSchemes() {
        for(String bad:new String[]{"javascript:alert(1)","data:text/html,x","data:image/svg+xml,x","//evil.example/a","https:\\evil.example","java\nscript:alert(1)"})
            assertFalse(ClassroomSlideSanitizer.safeUrl(bad),bad);
        for(String good:new String[]{"https://example.org/a.png","/assets/course.png","data:image/png;base64,YQ=="})
            assertTrue(ClassroomSlideSanitizer.safeUrl(good),good);
    }
}
