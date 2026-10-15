package com.demo.edu.student;

import com.demo.edu.mapper.EduStudentStatsMapper;
import com.demo.memory.MemoryRagService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 学生周报：把<b>学生自己</b>近 N 天的学习数据汇成一页 Word。
 *
 * <h3>三条刻意的设计约束</h3>
 * <ol>
 *   <li><b>只有学生主动点，才生成</b>——没有定时任务、没有自动上报。周报是给学生自己看的，
 *       不是给系统采集的；</li>
 *   <li><b>只查自己</b>——所有 SQL 都按 userId 过滤，服务端从请求取 id，不做跨用户聚合；</li>
 *   <li><b>不落盘</b>——直接返回字节流走下载。仓库里已有的 {@code FileGenService.generate()}
 *       会顺手把文件写到 D 盘沙箱，对"学生点下载"这个场景是多余的副作用
 *       （而且那个目录不可写时它会抛异常，把导出整个带崩）。这里自己用 POI 生成，
 *       但沿用它处理中文字体的做法（{@code FontCharRange.eastAsia}），否则中文在 Word 里会变方框。</li>
 * </ol>
 */
@Slf4j
@Service
public class EduWeeklyReportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Resource
    private EduStudentStatsMapper statsMapper;

    /** 本地学习档案（Lucene）。取不到时按 0 处理，不影响报告生成。 */
    @Resource
    private MemoryRagService memoryRagService;

    /**
     * 生成周报 docx 字节流。
     *
     * @param userId 学生标识
     * @param days   统计窗口天数，钳制到 1~30
     */
    public byte[] buildWeeklyReport(String userId, int days) {
        int window = Math.max(1, Math.min(days, 30));
        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusDays(window - 1L);
        long fromMs = fromDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        LocalDateTime fromTs = fromDate.atStartOfDay();

        int sessions = safeInt(() -> statsMapper.sessionCount(userId, fromMs));
        int asked = safeInt(() -> statsMapper.messageCount(userId, fromMs, "user"));
        int answered = safeInt(() -> statsMapper.messageCount(userId, fromMs, "assistant"));
        int quizTotal = safeInt(() -> statsMapper.quizAnswered(userId, fromTs));
        int quizCorrect = safeInt(() -> statsMapper.quizCorrect(userId, fromTs));
        int wrong = Math.max(0, quizTotal - quizCorrect);
        int localMemories = safeInt(() -> memoryRagService.count(userId));

        String rate = quizTotal == 0 ? "—" : Math.round(quizCorrect * 100.0 / quizTotal) + "%";

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"对话会话数", String.valueOf(sessions)});
        rows.add(new String[]{"我提出的问题", String.valueOf(asked)});
        rows.add(new String[]{"AI 回复条数", String.valueOf(answered)});
        rows.add(new String[]{"刷题作答", String.valueOf(quizTotal)});
        rows.add(new String[]{"答对", quizCorrect + "（正确率 " + rate + "）"});
        rows.add(new String[]{"错题", String.valueOf(wrong)});
        rows.add(new String[]{"本地学习档案",
                localMemories > 0 ? localMemories + " 条（只存在这台电脑上）" : "暂无"});

        List<String> notes = new ArrayList<>();
        notes.add("统计窗口：" + fromDate.format(DATE) + " ~ " + today.format(DATE)
                + "（共 " + window + " 天）");
        notes.add("");
        notes.add("写给自己的话：");
        notes.add(buildAdvice(sessions, asked, quizTotal, wrong));
        notes.add("");
        notes.add("说明：本报告只包含你自己的学习数据，只在你点击导出时生成，"
                + "系统不会自动上传，也不会分享给老师或其他人。"
                + "老师能看到的只有整个班级的匿名统计（例如「本周有多少人使用过」），"
                + "看不到任何人的对话内容。");

        String title = "我的学习周报";
        byte[] bytes = buildDocx(title, rows, notes);
        log.info("📄 学生周报已生成：用户哈希={} 窗口={}天 {}字节",
                Integer.toHexString(String.valueOf(userId).hashCode()), window, bytes.length);
        return bytes;
    }

    /** 依据数据给一句具体的鼓励，而不是千篇一律的套话。 */
    private static String buildAdvice(int sessions, int asked, int quizTotal, int wrong) {
        if (sessions == 0 && quizTotal == 0) {
            return "这周还没有开始学习的记录。要不要先问 AI 一个问题，或者做 5 道题热热身？";
        }
        StringBuilder sb = new StringBuilder();
        if (asked > 0) {
            sb.append("这周你一共提了 ").append(asked).append(" 个问题，主动提问是最好的学习方式。");
        }
        if (quizTotal > 0) {
            sb.append(wrong > 0
                    ? "做了 " + quizTotal + " 道题，错了 " + wrong + " 道——错题正是可以涨分的地方，去错题本再过一遍。"
                    : "做了 " + quizTotal + " 道题全部答对，可以试试更有挑战的题目。");
        }
        return sb.toString();
    }

    private byte[] buildDocx(String title, List<String[]> rows, List<String> paragraphs) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph titleParagraph = doc.createParagraph();
            titleParagraph.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titleParagraph.createRun();
            titleRun.setText(title);
            titleRun.setBold(true);
            titleRun.setFontSize(18);
            chineseFont(titleRun);

            for (String line : paragraphs) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun r = p.createRun();
                r.setFontSize(11);
                r.setText(line);
                chineseFont(r);
            }

            XWPFTable table = doc.createTable(rows.size() + 1, 2);
            XWPFTableRow header = table.getRow(0);
            setCell(header, 0, "项目", true);
            setCell(header, 1, "本周", true);
            for (int i = 0; i < rows.size(); i++) {
                XWPFTableRow row = table.getRow(i + 1);
                setCell(row, 0, rows.get(i)[0], false);
                setCell(row, 1, rows.get(i)[1], false);
            }

            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成周报失败：" + e.getMessage(), e);
        }
    }

    private static void setCell(XWPFTableRow row, int col, String text, boolean bold) {
        XWPFParagraph p = row.getCell(col).getParagraphs().get(0);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(bold);
        r.setFontSize(11);
        chineseFont(r);
    }

    /**
     * 中文字体：不设 eastAsia 字体时，Word 打开会显示成方框。
     * 与 {@code FileGenService.setChineseFont} 同一做法。
     */
    private static void chineseFont(XWPFRun run) {
        run.setFontFamily("微软雅黑", XWPFRun.FontCharRange.eastAsia);
    }

    /** 统计查询失败不该让周报生成不出来：记 WARN，该项按 0 计。 */
    private static int safeInt(java.util.function.IntSupplier supplier) {
        try {
            return supplier.getAsInt();
        } catch (Exception e) {
            log.warn("周报统计项查询失败（按 0 计）：{}", e.toString());
            return 0;
        }
    }
}
