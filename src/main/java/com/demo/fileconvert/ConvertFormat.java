package com.demo.fileconvert;

import java.util.Set;

/**
 * 转换格式枚举 —— 输入白名单 + 输出白名单 + 引擎归属的唯一事实源。
 * <p>
 * 新增一种格式只需在此枚举中注册：
 * <ul>
 *   <li>输入白名单 = 枚举全部值的扩展名</li>
 *   <li>输出白名单 = 各分类的 {@code *_TARGETS} 集合</li>
 *   <li>引擎归属 = 分类决定（DOCUMENT→LibreOffice，IMAGE→ImageMagick）</li>
 * </ul>
 */
public enum ConvertFormat {

    // ---- 文档类（LibreOffice）----
    PDF("pdf", Category.DOCUMENT),
    DOCX("docx", Category.DOCUMENT),
    DOC("doc", Category.DOCUMENT),
    XLS("xls", Category.DOCUMENT),
    XLSX("xlsx", Category.DOCUMENT),
    PPT("ppt", Category.DOCUMENT),
    PPTX("pptx", Category.DOCUMENT),
    TXT("txt", Category.DOCUMENT),
    MD("md", Category.DOCUMENT),
    RTF("rtf", Category.DOCUMENT),
    HTML("html", Category.DOCUMENT),

    // ---- 图片类（ImageMagick）----
    JPG("jpg", Category.IMAGE),
    JPEG("jpeg", Category.IMAGE),
    PNG("png", Category.IMAGE),
    GIF("gif", Category.IMAGE),
    WEBP("webp", Category.IMAGE),
    HEIC("heic", Category.IMAGE),
    HEIF("heif", Category.IMAGE),
    TIFF("tiff", Category.IMAGE),
    BMP("bmp", Category.IMAGE),
    JXL("jxl", Category.IMAGE);

    /** 文档输出白名单（LibreOffice 支持的目标格式） */
    private static final Set<String> DOC_TARGETS = Set.of("pdf", "docx", "txt", "md", "rtf", "html");

    /** 图片输出白名单（ImageMagick 支持的目标格式） */
    private static final Set<String> IMG_TARGETS = Set.of("jpg", "png", "webp");

    public enum Category { DOCUMENT, IMAGE }

    public enum Engine { LIBREOFFICE, IMAGEMAGICK }

    private final String ext;
    private final Category category;

    ConvertFormat(String ext, Category category) {
        this.ext = ext;
        this.category = category;
    }

    public String ext() { return ext; }
    public Category category() { return category; }

    // ======================== 静态 API ========================

    /** 规范化扩展名：去前导点、转小写、空串保护 */
    public static String normalize(String ext) {
        if (ext == null) return "";
        String e = ext.trim().toLowerCase();
        while (e.startsWith(".")) e = e.substring(1);
        return e;
    }

    /** 是否为受支持的白名单输入格式（去扩展名，仅对比扩展名本身） */
    public static boolean isSupportedInput(String ext) {
        String e = normalize(ext);
        for (ConvertFormat f : values()) {
            if (f.ext.equals(e)) return true;
        }
        return false;
    }

    /** 目标格式是否为该分类允许输出的格式 */
    public static boolean isSupportedTarget(String sourceExt, String targetExt) {
        String s = normalize(sourceExt);
        String t = normalize(targetExt);
        Category cat = categoryOf(s);
        if (cat == null) return false;
        return cat == Category.DOCUMENT ? DOC_TARGETS.contains(t) : IMG_TARGETS.contains(t);
    }

    /** 输入与目标格式是否可转换（同分类 + 输入在白名单 + 目标在输出白名单） */
    public static boolean supports(String sourceExt, String targetExt) {
        String s = normalize(sourceExt);
        String t = normalize(targetExt);
        if (!isSupportedInput(s)) return false;
        if (s.equals(t)) return true;              // 同格式直接视为可转换（引擎空转）
        return isSupportedTarget(s, t);
    }

    /** 返回格式所属分类；未知格式返回 null */
    public static Category categoryOf(String ext) {
        String e = normalize(ext);
        for (ConvertFormat f : values()) {
            if (f.ext.equals(e)) return f.category;
        }
        return null;
    }

    /** 返回格式对应的转换引擎；未知格式返回 null */
    public static Engine engineFor(String ext) {
        Category cat = categoryOf(ext);
        if (cat == null) return null;
        return cat == Category.DOCUMENT ? Engine.LIBREOFFICE : Engine.IMAGEMAGICK;
    }

    /** 分类中文描述（日志/提示用） */
    public static String categoryName(String ext) {
        Category cat = categoryOf(ext);
        return cat == null ? "未知" : (cat == Category.DOCUMENT ? "文档" : "图片");
    }
}
