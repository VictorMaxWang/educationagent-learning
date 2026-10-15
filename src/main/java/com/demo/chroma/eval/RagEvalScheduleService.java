package com.demo.chroma.eval;

import com.demo.entity.RagEvalScheduleEntity;
import com.demo.mapper.RagEvalScheduleMapper;
import com.demo.service.tools.ReportScheduleService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 周度评估调度。
 *
 * <p>复用 {@link ReportScheduleService} 的通用周期任务线程池（与小红书热门视频周报同一套），
 * 配置持久化在 {@code rag_eval_schedule} 单行表里，可运行时开关与改期。</p>
 */
@Slf4j
@Service
@DependsOn("databaseMigration")
public class RagEvalScheduleService {

    private static final String JOB_ID = "rag-eval";

    /** 与 ReportScheduleService 一致：固定东八区 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter NEXT_RUN_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Resource
    private RagEvalScheduleMapper scheduleMapper;

    @Resource
    private ReportScheduleService reportScheduleService;

    @Resource
    private RagEvalService ragEvalService;

    @Value("${rag.eval.default-collection:travel_knowledge}")
    private String defaultCollection;

    @Value("${rag.eval.default-sample-size:20}")
    private int defaultSampleSize;

    @Value("${rag.eval.default-top-k:5}")
    private int defaultTopK;

    @Value("${rag.eval.default-days:1}")
    private String defaultDays;

    @Value("${rag.eval.default-time:03:00}")
    private String defaultTime;

    @Value("${rag.eval.max-top-k:20}")
    private int maxTopK;

    @PostConstruct
    public void init() {
        RagEvalScheduleEntity cfg = getOrCreateConfig();
        log.info("📊 评估调度配置: enabled={} days={} time={} collection={} sample_size={} top_k={}",
                cfg.getEnabled(), cfg.getDays(), cfg.getTime(), cfg.getCollection(),
                cfg.getSampleSize(), cfg.getTopK());
        applySchedule(cfg);
    }

    // ======================== 配置读写 ========================

    /** 读单行配置；不存在则插默认值（默认关闭，避免部署即开始烧 LLM 额度） */
    public RagEvalScheduleEntity getOrCreateConfig() {
        List<RagEvalScheduleEntity> rows = scheduleMapper.selectList(null);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        RagEvalScheduleEntity c = new RagEvalScheduleEntity();
        c.setEnabled(0);
        c.setDays(defaultDays);
        c.setTime(defaultTime);
        c.setCollection(defaultCollection);
        c.setSampleSize(defaultSampleSize);
        c.setTopK(defaultTopK);
        scheduleMapper.insert(c);
        log.info("📊 已初始化默认评估调度配置 (enabled=0, 每周{} {} 评估 {})",
                defaultDays, defaultTime, defaultCollection);
        return c;
    }

    /** 周几+时分 → Spring 6 段 cron：0 &lt;min&gt; &lt;hour&gt; * * &lt;days&gt;（1=周一..7=周日） */
    private String toCron(String days, String time) {
        String t = (time == null || time.isBlank()) ? defaultTime : time.trim();
        if (!t.matches("\\d{1,2}:\\d{2}")) {
            throw new IllegalArgumentException("time 格式必须是 HH:mm，收到：" + time);
        }
        String[] parts = t.split(":");
        int hour = Integer.parseInt(parts[0].trim());
        int minute = Integer.parseInt(parts[1].trim());
        if (hour > 23 || minute > 59) {
            throw new IllegalArgumentException("time 取值非法（hour 0~23, minute 0~59）：" + time);
        }

        String d = (days == null || days.isBlank()) ? defaultDays : days.trim();
        for (String s : d.split(",")) {
            int v;
            try {
                v = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("days 必须是逗号分隔的数字，收到：" + days);
            }
            if (v < 1 || v > 7) {
                throw new IllegalArgumentException("days 取值必须在 1~7（1=周一..7=周日），收到：" + s);
            }
        }
        return String.format("0 %d %d * * %s", minute, hour, d);
    }

    /** 当前状态（含下次执行时间） */
    public Map<String, Object> status() {
        RagEvalScheduleEntity c = getOrCreateConfig();
        String cron = "";
        String nextRun = "";
        try {
            cron = toCron(c.getDays(), c.getTime());
            // 必须用 ZonedDateTime：带星期字段的 cron 对 Instant 会抛 Unsupported field: DayOfWeek
            ZonedDateTime next = CronExpression.parse(cron).next(ZonedDateTime.now(ZONE));
            if (next != null) {
                nextRun = next.format(NEXT_RUN_FMT);
            }
        } catch (Exception e) {
            log.warn("📊 计算评估下次执行时间失败: {}", e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.getEnabled() != null && c.getEnabled() == 1);
        m.put("days", c.getDays());
        m.put("time", c.getTime());
        m.put("collection", c.getCollection());
        m.put("sample_size", c.getSampleSize());
        m.put("top_k", c.getTopK());
        m.put("cron", cron);
        m.put("nextRun", nextRun);
        return m;
    }

    /**
     * 设置调度（开关 + 周几 + 时分 + 评估参数），持久化并重排。
     *
     * <p>先整体校验再落库，避免出现「days 改了、time 没改」的中间态。</p>
     */
    public Map<String, Object> setSchedule(Boolean enabled, String days, String time,
                                          String collection, Integer sampleSize, Integer topK) {
        RagEvalScheduleEntity c = getOrCreateConfig();

        String newDays = (days != null && !days.isBlank()) ? days.trim() : c.getDays();
        String newTime = (time != null && !time.isBlank()) ? time.trim() : c.getTime();

        // 校验（会抛 IllegalArgumentException，由 Controller 转 400）
        if (days != null || time != null) {
            toCron(newDays, newTime);
        }
        if (sampleSize != null && sampleSize < 1) {
            throw new IllegalArgumentException("sample_size 必须大于 0");
        }
        if (topK != null && (topK < 1 || topK > maxTopK)) {
            throw new IllegalArgumentException("top_k 必须在 1~" + maxTopK + " 之间");
        }

        c.setDays(newDays);
        c.setTime(newTime);
        if (collection != null && !collection.isBlank()) c.setCollection(collection.trim());
        if (sampleSize != null) c.setSampleSize(sampleSize);
        if (topK != null) c.setTopK(topK);
        if (enabled != null) c.setEnabled(enabled ? 1 : 0);

        scheduleMapper.updateById(c);
        applySchedule(c);
        log.info("📊 评估调度已更新: enabled={} days={} time={} collection={}",
                c.getEnabled(), c.getDays(), c.getTime(), c.getCollection());
        return status();
    }

    // ======================== 调度装配 ========================

    /** 按配置注册/取消定时任务（复用 ReportScheduleService 线程池） */
    private void applySchedule(RagEvalScheduleEntity c) {
        reportScheduleService.cancelRecurring(JOB_ID);
        if (c.getEnabled() == null || c.getEnabled() != 1) {
            return;
        }
        String cron;
        try {
            cron = toCron(c.getDays(), c.getTime());
            // 提前校验 cron 可解析：scheduleRecurring 解析失败是静默 return，
            // 会让任务悄无声息地不生效（没有异常、没有日志说「没排上」）
            CronExpression.parse(cron);
        } catch (Exception e) {
            log.error("📊 评估调度配置非法，定时任务未生效: {}", e.getMessage());
            return;
        }
        reportScheduleService.scheduleRecurring(JOB_ID, this::triggerScheduledRun, cron);
    }

    /** 定时触发：用配置表里的参数跑一轮评估 */
    private void triggerScheduledRun() {
        RagEvalScheduleEntity c = getOrCreateConfig();
        String collection = (c.getCollection() == null || c.getCollection().isBlank())
                ? defaultCollection : c.getCollection();
        int sampleSize = c.getSampleSize() != null ? c.getSampleSize() : defaultSampleSize;
        int topK = c.getTopK() != null ? c.getTopK() : defaultTopK;
        try {
            String runId = ragEvalService.startRun(collection, topK, sampleSize, false);
            log.info("📊 定时评估已触发: run_id={} collection={} sample_size={} top_k={}",
                    runId, collection, sampleSize, topK);
        } catch (Exception e) {
            // 不能因为一次失败就让定时任务停摆：scheduleRecurring 内部会在 finally 里自重排下一次
            log.error("📊 定时评估触发失败: {}", e.getMessage());
        }
    }
}
