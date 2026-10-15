package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 编辑/导入边界的真实变换测试，不启动服务或调用模型。 */
class ClassroomEditingSupportTest {
    final ObjectMapper json=new ObjectMapper();

    @Test void rejectsNonFiniteGeometryDuplicateIdsAndUnsafeMedia() {
        ObjectNode canvas=canvas();
        ObjectNode duplicate=((ObjectNode)canvas.path("elements").get(0)).deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode)canvas.path("elements")).add(duplicate);
        assertThrows(ClassroomException.class,()->ClassroomEditingSupport.canvas(canvas));
        ObjectNode badNumber=canvas(); ((ObjectNode)badNumber.path("elements").get(0)).put("left",Double.POSITIVE_INFINITY);
        assertThrows(ClassroomException.class,()->ClassroomEditingSupport.canvas(badNumber));
        ObjectNode unsafe=canvas(); ((ObjectNode)unsafe.path("elements").get(0)).put("type","image").put("src","javascript:alert(1)");
        assertThrows(ClassroomException.class,()->ClassroomEditingSupport.canvas(unsafe));
        ObjectNode polluted=json.createObjectNode(); polluted.putObject("__proto__").put("x",1);
        assertThrows(ClassroomException.class,()->ClassroomEditingSupport.boundedState(polluted,1000));
    }

    @Test void selfContainedInteractiveImportHasStrictCspAndRetainsWorkerCapability() {
        String html="<html><head><meta http-equiv='Content-Security-Policy' content=\"default-src * 'unsafe-eval'\"><script src='https://bad.example/a.js'></script></head>"
                +"<body><iframe srcdoc='evil'></iframe><form action='https://bad.example'><input id='mass'></form><a href='https://bad.example'>link</a>"
                +"<script>const value=3;const worker=new Worker(URL.createObjectURL(new Blob(['postMessage(1)'])));</script></body></html>";
        String cleaned=ClassroomSlideSanitizer.cleanInteractiveHtml(html); var document=Jsoup.parse(cleaned);
        assertEquals(0,document.select("iframe,form,script[src]").size());
        assertNotNull(document.getElementById("mass"));
        String csp=document.head().children().getFirst().attr("content");
        assertTrue(csp.contains("connect-src 'none'")); assertTrue(csp.contains("worker-src blob:"));
        assertFalse(csp.contains("unsafe-eval")); assertFalse(csp.contains("allow-same-origin"));
        assertTrue(cleaned.contains("const value=3")); assertFalse(cleaned.contains("https://bad.example"));
    }

    @Test void interactiveReferencesAndCommandsUseSavedUniqueIds() {
        ObjectNode scene=json.createObjectNode().put("id","s").put("type","interactive");
        scene.putObject("content").put("type","interactive").put("html","<html><body><div id='plot'><input id='mass' value='2'></div><script id='script'>const a=1;</script></body></html>");
        ObjectNode reference=json.createObjectNode().put("kind","interactive_component").put("sceneId","s").put("selector","#mass");
        var evidence=ClassroomElementResolver.resolve(json.createObjectNode(),scene,reference);
        assertEquals("2",evidence.path("initialValue").asText());
        reference.put("selector","#made-up-at-runtime");
        assertThrows(ClassroomException.class,()->ClassroomElementResolver.resolve(json.createObjectNode(),scene,reference));
        reference.put("selector","#script");
        assertThrows(ClassroomException.class,()->ClassroomElementResolver.resolve(json.createObjectNode(),scene,reference));
        ObjectNode action=json.createObjectNode().put("type","widget_highlight").put("target","#mass");
        assertEquals(1,ClassroomActionPolicy.validate(json.createArrayNode().add(action),scene,json.createObjectNode(),true).size());
        assertTrue(ClassroomActionPolicy.validate(json.createArrayNode().add(action),scene,json.createObjectNode(),false).isEmpty());
        action.put("target","body script");
        assertThrows(ClassroomException.class,()->ClassroomActionPolicy.validate(json.createArrayNode().add(action),scene,json.createObjectNode(),true));
        ObjectNode observation=json.createObjectNode().put("sourceHtmlHash","stale");
        assertThrows(ClassroomException.class,()->ClassroomElementResolver.observation(scene,observation));
    }

    @Test void whiteboardActionsValidateSequentialIdentityAndFiniteCoordinates() {
        ObjectNode scene=json.createObjectNode().put("type","slide"); scene.putObject("content").set("canvas",canvas());
        var actions=json.createArrayNode();
        actions.addObject().put("type","wb_draw_text").put("elementId","wb-1").put("content","<b>分数</b><img src=x onerror='bad()'>").put("x",30).put("y",40);
        actions.addObject().put("type","wb_delete").put("elementId","wb-1");
        var valid=ClassroomActionPolicy.validate(actions,scene,json.createObjectNode(),true);
        assertEquals(2,valid.size()); assertFalse(valid.get(0).path("content").asText().contains("onerror"));
        ((ObjectNode)actions.get(0)).put("x",Double.NaN);
        assertThrows(ClassroomException.class,()->ClassroomActionPolicy.validate(actions,scene,json.createObjectNode(),true));
        var unknown=json.createArrayNode(); unknown.addObject().put("type","wb_delete").put("elementId","absent");
        assertThrows(ClassroomException.class,()->ClassroomActionPolicy.validate(unknown,scene,json.createObjectNode(),true));
    }

    @Test void codeLinesRemainPlainCodeAndDeletedElementsLoseStaleSpotlight() {
        ObjectNode scene=json.createObjectNode().put("id","s").put("title","课件").put("type","slide").put("order",0);
        scene.putObject("content").put("type","slide").set("canvas",canvas());
        scene.putArray("actions").addObject().put("id","point").put("type","spotlight").put("elementId","e");
        ObjectNode content=json.createObjectNode().put("type","slide");
        ObjectNode replacement=json.createObjectNode();
        ObjectNode code=replacement.putArray("elements").addObject().put("id","code").put("type","code").put("left",0).put("top",0).put("width",500).put("height",300);
        code.putArray("lines").addObject().put("id","line-1").put("content","if (x < 2) console.log('<b>');");
        content.set("canvas",replacement);
        ObjectNode saved=ClassroomEditingSupport.editedScene(scene,content,null,"course");
        assertEquals("if (x < 2) console.log('<b>');",saved.path("content").path("canvas").path("elements").get(0).path("lines").get(0).path("content").asText());
        assertTrue(saved.path("actions").isEmpty());
    }

    @Test void tableCommandsAreSafeBeforeBrowserPersistence() {
        ObjectNode scene=json.createObjectNode().put("type","slide"); scene.putObject("content").set("canvas",canvas());
        var actions=json.createArrayNode();
        ObjectNode table=actions.addObject().put("type","wb_draw_table").put("x",0).put("y",0).put("width",400).put("height",200);
        table.putArray("data").addArray().add("<b>正常</b><img src=x onerror='bad()'>");
        var checked=ClassroomActionPolicy.validate(actions,scene,json.createObjectNode(),true);
        assertFalse(checked.get(0).path("data").get(0).get(0).asText().contains("onerror"));
        table.putObject("theme").put("color","url(https://invalid.example/x)");
        assertThrows(ClassroomException.class,()->ClassroomActionPolicy.validate(actions,scene,json.createObjectNode(),true));
    }

    private ObjectNode canvas() {
        ObjectNode canvas=json.createObjectNode();
        canvas.putArray("elements").addObject().put("id","e").put("type","text").put("left",10).put("top",10).put("width",100).put("height",50).put("content","文字");
        return canvas;
    }

    @Test void tableDrawingBudgetMatchesTheBrowserRenderer() {
        ObjectNode scene=json.createObjectNode().put("type","slide"); scene.putObject("content").set("canvas",canvas());
        for(int[] size:new int[][]{{30,15},{31,1},{1,16}}) {
            var actions=json.createArrayNode();
            ObjectNode table=actions.addObject().put("type","wb_draw_table").put("x",0).put("y",0).put("width",400).put("height",200);
            var data=table.putArray("data");
            for(int row=0;row<size[0];row++) { var cells=data.addArray(); for(int column=0;column<size[1];column++) cells.add("内容"); }
            if(size[0]==30) assertEquals(1,ClassroomActionPolicy.validate(actions,scene,json.createObjectNode(),true).size());
            else assertThrows(ClassroomException.class,()->ClassroomActionPolicy.validate(actions,scene,json.createObjectNode(),true));
        }
    }
}
