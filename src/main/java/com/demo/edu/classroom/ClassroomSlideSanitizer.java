package com.demo.edu.classroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import java.net.URI;
import java.util.*;

/**
 * 教学画布渲染在主页面中，富文本必须在存储前清洗，不能依靠互动 iframe 的沙箱。
 * 保留文字、基础排版和 KaTeX 的有限 SVG 路径；不接受外部 SVG、脚本或 HTML 嵌入对象。
 */
final class ClassroomSlideSanitizer {
    private static final Set<String> URL_FIELDS = Set.of("src","href","url","pattern","poster","backgroundimage");
    private static final Set<String> CSS_VALUE_FIELDS = Set.of("color","fill","stroke","background","filter","fontfamily","defaultfontname");
    private static final Set<String> CSS_PROPERTIES = Set.of("color","background-color","font-family","font-size","font-weight",
            "font-style","text-align","text-decoration","line-height","letter-spacing","vertical-align","white-space",
            "margin","margin-left","margin-right","margin-top","margin-bottom","padding","padding-left","padding-right",
            "padding-top","padding-bottom","height","width","min-width","max-width","top","bottom","left","right",
            "position","display","border-color","border-width","border-top-width","border-bottom-width","border-style",
            "opacity","transform","transform-origin");
    private static final Safelist TEXT = new Safelist()
            .addTags("p","br","span","b","strong","i","em","u","s","sub","sup","ul","ol","li","div",
                    "h1","h2","h3","h4","blockquote","code","pre","table","thead","tbody","tr","th","td",
                    "svg","g","path","line","rect","polygon","polyline","circle","ellipse",
                    "math","semantics","annotation","mrow","mi","mn","mo","mspace","msup","msub","msubsup",
                    "mfrac","mover","munder","munderover","msqrt","mroot","mtable","mtr","mtd","mtext","menclose")
            .addAttributes(":all","style","class","aria-hidden")
            .addAttributes("span","data-inline-math")
            .addAttributes("td","colspan","rowspan").addAttributes("th","colspan","rowspan")
            .addAttributes("svg","width","height","viewbox","preserveaspectratio")
            .addAttributes("g","transform","fill","stroke")
            .addAttributes("path","d","fill","stroke","stroke-width","transform")
            .addAttributes("line","x1","y1","x2","y2","stroke","stroke-width")
            .addAttributes("rect","x","y","width","height","rx","ry","fill","stroke")
            .addAttributes("circle","cx","cy","r","fill","stroke")
            .addAttributes("ellipse","cx","cy","rx","ry","fill","stroke")
            .addAttributes("polygon","points","fill","stroke").addAttributes("polyline","points","fill","stroke")
            .addAttributes("math","display").addAttributes("annotation","encoding")
            .addAttributes("mo","stretchy","fence","separator","lspace","rspace")
            .addAttributes("mspace","width","height","depth");

    private ClassroomSlideSanitizer() {}
    static ObjectNode cleanScene(ObjectNode scene) {
        if ("slide".equals(scene.path("type").asText())) cleanTree(scene.path("content").path("canvas"));
        if (scene.has("whiteboards")) cleanTree(scene.path("whiteboards"));
        return scene;
    }

    private static void cleanTree(JsonNode node) {
        if (node.isArray()) { for (JsonNode child : node) cleanTree(child); return; }
        if (!node.isObject()) return;
        ObjectNode object=(ObjectNode)node;
        List<String> keys=new ArrayList<>(); object.fieldNames().forEachRemaining(keys::add);
        boolean code="code".equals(object.path("type").asText());
        for(String key:keys) {
            JsonNode value=object.get(key); String lower=key.toLowerCase(Locale.ROOT);
            if(lower.startsWith("on") || Set.of("srcdoc","innerhtml","outerhtml","foreignobject","script").contains(lower)) { object.remove(key); continue; }
            if(lower.equals("link")&&value.isObject()&&!safeUrl(value.path("target").asText())) { object.remove(key); continue; }
            if(value.isTextual()) {
                String text=value.asText();
                if(URL_FIELDS.contains(lower)) {
                    if(!safeUrl(text)) object.put(key,"");
                } else if((lower.equals("content")&&!code) || lower.equals("text") || lower.equals("html") || lower.equals("svg")) {
                    object.put(key,cleanHtml(text));
                } else if(lower.equals("style")) object.put(key,cleanStyle(text));
                else if(CSS_VALUE_FIELDS.contains(lower)&&containsActiveCss(text)) object.put(key,"");
            } else cleanTree(value);
        }
    }

    static String cleanHtml(String html) {
        Document document=Jsoup.parseBodyFragment(Jsoup.clean(html,"",TEXT,new Document.OutputSettings().prettyPrint(false)));
        document.outputSettings().prettyPrint(false);
        for(Element element:document.getAllElements()) {
            if(element.hasAttr("style")) {
                String style=cleanStyle(element.attr("style"));
                if(style.isBlank()) element.removeAttr("style"); else element.attr("style",style);
            }
            // 允许的 SVG 绘制属性也不能引用外部 paint server / CSS URL。
            for(String attr:List.of("fill","stroke","transform"))
                if(element.hasAttr(attr)&&containsActiveCss(element.attr(attr))) element.removeAttr(attr);
            if(element.hasAttr("data-inline-math")&&element.attr("data-inline-math").length()>2000) element.removeAttr("data-inline-math");
        }
        return document.body().html();
    }

    private static String cleanStyle(String text) {
        StringBuilder safe=new StringBuilder();
        for(String declaration:text.split(";")) {
            int colon=declaration.indexOf(':'); if(colon<1) continue;
            String property=declaration.substring(0,colon).trim().toLowerCase(Locale.ROOT);
            String value=declaration.substring(colon+1).trim();
            if(!CSS_PROPERTIES.contains(property)||value.length()>300||containsActiveCss(value)||!value.matches("[\\p{L}\\p{N}\\s#.,()%+\\-\"']+")) continue;
            if(property.equals("position")&&!Set.of("relative","static").contains(value)) continue;
            safe.append(property).append(':').append(value).append(';');
        }
        return safe.toString();
    }

    private static boolean containsActiveCss(String value) {
        String lower=value.toLowerCase(Locale.ROOT).replaceAll("\\s+","");
        return lower.contains("url(")||lower.contains("expression(")||lower.contains("javascript:")||lower.contains("vbscript:")
                ||lower.contains("@import")||lower.contains("-moz-binding")||lower.contains("behavior:")||lower.contains("\\");
    }

    static boolean safeUrl(String text) {
        if(text==null||text.isBlank()) return true;
        if(text.chars().anyMatch(c -> Character.isISOControl(c)||Character.isWhitespace(c))||text.contains("\\")) return false;
        if(text.matches("(?i)^data:image/(png|jpeg|jpg|gif|webp);base64,[A-Za-z0-9+/=]+$")) return true;
        try {
            URI uri=URI.create(text);
            if(uri.getScheme()==null) return !text.startsWith("//")&&!text.startsWith("#")&&!text.contains(":");
            return Set.of("https","http").contains(uri.getScheme().toLowerCase(Locale.ROOT))&&uri.getHost()!=null&&uri.getUserInfo()==null;
        } catch(IllegalArgumentException invalid) { return false; }
    }
}
