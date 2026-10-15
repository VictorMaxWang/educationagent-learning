package com.demo.pet;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 宠物健康与成长服务 — 体重/疫苗/驱虫/用药/日记/花费/症状/健康评分
 * 基于通用记录表 pet_records（record_type 区分记录类型）
 */
@Slf4j
@Service
public class PetHealthService {

    @Resource private PetRecordMapper recordMapper;
    @Resource private PetService petService;

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ==================== 体重管理 ====================

    public String logWeight(String userId, String petName, double weightKg, String note) {
        petService.getOrCreate(userId, petName);
        PetRecordEntity log = PetRecordEntity.weight(userId, petName, weightKg, LocalDate.now(), note);
        recordMapper.insert(log);

        PetRecordEntity prev = getPreviousWeight(userId, petName);
        StringBuilder sb = new StringBuilder();
        sb.append("✅ ").append(petName).append(" 体重已记录: ").append(weightKg).append("kg");
        if (prev != null) {
            double diff = weightKg - prev.getWeightKg();
            String trend = diff > 0.3 ? " (↑" + String.format("%.1f", diff) + "kg ⚠️)" :
                           diff < -0.3 ? " (↓" + String.format("%.1f", Math.abs(diff)) + "kg)" :
                           " (→ 稳定)";
            sb.append(trend);
        }
        // 更新pets表最新体重
        try { petService.update(userId, petName, "weightKg", String.valueOf(weightKg)); } catch (Exception ignored) {}
        return sb.toString();
    }

    private PetRecordEntity getPreviousWeight(String userId, String petName) {
        List<PetRecordEntity> logs = recordMapper.findByUserAndPet(userId, petName, PetRecordEntity.TYPE_WEIGHT);
        return logs.size() >= 2 ? logs.get(1) : null; // skip the latest
    }

    public String queryWeightHistory(String userId, String petName) {
        List<PetRecordEntity> logs = recordMapper.findByUserAndPet(userId, petName, PetRecordEntity.TYPE_WEIGHT);
        if (logs.isEmpty()) return "📊 " + petName + " 暂无体重记录。说'" + petName + "X公斤'即可记录。";
        StringBuilder sb = new StringBuilder("📊 " + petName + " 体重变化：\n\n");
        double min = logs.stream().mapToDouble(PetRecordEntity::getWeightKg).min().orElse(0);
        double max = logs.stream().mapToDouble(PetRecordEntity::getWeightKg).max().orElse(0);
        for (int i = 0; i < Math.min(logs.size(), 12); i++) {
            PetRecordEntity w = logs.get(i);
            String bar = weightBar(w.getWeightKg(), min, max);
            sb.append(w.getRecordDate()).append("  ").append(bar).append(" ").append(w.getWeightKg()).append("kg\n");
        }
        if (logs.size() >= 2) {
            double first = logs.get(Math.min(logs.size(), 12) - 1).getWeightKg();
            double last = logs.get(0).getWeightKg();
            double change = last - first;
            sb.append("\n趋势: ").append(change > 0.3 ? "📈 上升" : change < -0.3 ? "📉 下降" : "➡️ 稳定");
        }
        return sb.toString();
    }

    private String weightBar(double val, double min, double max) {
        double range = max == min ? 1 : max - min;
        int len = (int) ((val - min) / range * 10);
        return "█".repeat(Math.max(0, len)) + "░".repeat(Math.max(0, 10 - len));
    }

    // ==================== 疫苗管理 ====================

    /** 疫苗周期映射 */
    private static final Map<String, Integer> VACCINE_INTERVALS = Map.of(
            "狂犬", 365, "犬瘟", 365, "细小", 365, "肝炎", 365, "副流感", 365,
            "猫三联", 365, "钩端螺旋体", 365, "犬窝咳", 180
    );

    public String logVaccine(String userId, String petName, String vaccineName) {
        petService.getOrCreate(userId, petName);
        LocalDate today = LocalDate.now();
        int interval = VACCINE_INTERVALS.getOrDefault(vaccineName, 365);
        LocalDate next = today.plusDays(interval);

        PetRecordEntity v = PetRecordEntity.vaccine(userId, petName, vaccineName, today, next);
        recordMapper.insert(v);

        return "✅ " + petName + " " + vaccineName + "疫苗已记录。下次接种: " + next + "（自动提醒）";
    }

    public String queryVaccineStatus(String userId, String petName) {
        List<PetRecordEntity> list = recordMapper.findByUserAndPet(userId, petName, PetRecordEntity.TYPE_VACCINE);
        if (list.isEmpty()) return "💉 " + petName + " 暂无疫苗记录。";

        StringBuilder sb = new StringBuilder("💉 " + petName + " 疫苗情况：\n\n");
        Map<String, PetRecordEntity> latest = new LinkedHashMap<>();
        for (PetRecordEntity v : list) {
            latest.putIfAbsent(v.getVaccineName(), v);
        }
        LocalDate today = LocalDate.now();
        for (PetRecordEntity v : latest.values()) {
            long daysLeft = ChronoUnit.DAYS.between(today, v.getNextDate());
            String status = daysLeft < 0 ? "⚠️ 已过期" : daysLeft <= 14 ? "🔶 临近" : "✅ 有效";
            sb.append(String.format("  %-10s  接种:%s  下次:%s  %s%n",
                    v.getVaccineName(), v.getVaccDate(), v.getNextDate(), status));
        }
        return sb.toString();
    }

    // ==================== 驱虫管理 ====================

    public String logDeworming(String userId, String petName, String dewormType, String drugName) {
        petService.getOrCreate(userId, petName);
        LocalDate today = LocalDate.now();
        int interval = "体内".equals(dewormType) ? 90 : 30;
        LocalDate next = today.plusDays(interval);
        PetRecordEntity d = PetRecordEntity.deworm(userId, petName, dewormType, today, next, drugName);
        recordMapper.insert(d);
        return "✅ " + petName + " " + dewormType + "驱虫已记录。下次: " + next;
    }

    public String queryDewormingStatus(String userId, String petName) {
        List<PetRecordEntity> list = recordMapper.findByUserAndPet(userId, petName, PetRecordEntity.TYPE_DEWORM);
        if (list.isEmpty()) return "🪱 " + petName + " 暂无驱虫记录。";

        Map<String, PetRecordEntity> latest = new LinkedHashMap<>();
        for (PetRecordEntity d : list) latest.putIfAbsent(d.getDewormType(), d);
        LocalDate today = LocalDate.now();
        StringBuilder sb = new StringBuilder("🪱 " + petName + " 驱虫情况：\n\n");
        for (PetRecordEntity d : latest.values()) {
            long daysLeft = ChronoUnit.DAYS.between(today, d.getNextDate());
            String status = daysLeft < 0 ? "⚠️ 已过期" : daysLeft <= 7 ? "🔶 临近" : "✅ 有效";
            sb.append(String.format("  %s  上次:%s  下次:%s  %s%n",
                    d.getDewormType(), d.getDewormDate(), d.getNextDate(), status));
        }
        return sb.toString();
    }

    // ==================== 用药提醒 ====================

    public String logMedication(String userId, String petName, String drugName,
                                 String startDate, String endDate, int timesPerDay, String dosage) {
        petService.getOrCreate(userId, petName);
        LocalDate start = LocalDate.parse(startDate);
        LocalDate end = LocalDate.parse(endDate);
        PetRecordEntity m = PetRecordEntity.medication(userId, petName, drugName, start, end,
                timesPerDay > 0 ? timesPerDay : 1, dosage);

        // 自动生成用药描述
        String freq = timesPerDay == 1 ? "每天1次" : timesPerDay == 2 ? "每天早晚各1次" : timesPerDay == 3 ? "每天3次" : "每天" + timesPerDay + "次";
        long totalDays = ChronoUnit.DAYS.between(start, end) + 1;
        String note = freq + "，共" + totalDays + "天" + (dosage != null ? "，每次" + dosage : "");
        m.setText1(note);   // 直接用自动生成的描述作为 text1（与 dosage 合并，查询时无需再拼）
        recordMapper.insert(m);

        return "💊 " + petName + " 用药提醒已设置：\n"
                + "  药物: " + drugName + "\n"
                + "  周期: " + start + " ~ " + end + "（" + totalDays + "天）\n"
                + "  频率: " + note + "\n"
                + "  到点会自动推送提醒";
    }

    public String queryMedications(String userId, String petName) {
        List<PetRecordEntity> list = recordMapper.findByUserAndPet(userId, petName, PetRecordEntity.TYPE_MEDICATION);
        if (list.isEmpty()) return "💊 " + petName + " 暂无用药记录。";

        StringBuilder sb = new StringBuilder("💊 " + petName + " 用药记录：\n\n");
        for (PetRecordEntity m : list) {
            String status = m.getActive() != null && m.getActive() == 1 ? "🟢 进行中" : "⚪ 已结束";
            sb.append("  ").append(status).append(" ").append(m.getDrugName())
                    .append(" ").append(m.getStartDate()).append("~").append(m.getEndDate())
                    .append(" ").append(m.getNote() != null ? m.getNote() : "").append("\n");
        }
        return sb.toString();
    }

    // ==================== 成长日记 ====================

    public String logGrowth(String userId, String petName, String eventType, String content) {
        petService.getOrCreate(userId, petName);
        PetRecordEntity g = PetRecordEntity.growth(userId, petName, eventType != null ? eventType : "note",
                LocalDate.now(), content);
        recordMapper.insert(g);
        return "📝 " + petName + " 成长日记已记录: " + content.substring(0, Math.min(30, content.length())) + "...";
    }

    public String queryGrowthDiary(String userId, String petName) {
        List<PetRecordEntity> list = recordMapper.findByUserAndPet(userId, petName, PetRecordEntity.TYPE_GROWTH);
        if (list.isEmpty()) return "📔 " + petName + " 暂无成长记录。说'" + petName + "今天xxx'即可记录。";

        StringBuilder sb = new StringBuilder("📔 " + petName + " 成长日记：\n\n");
        String[] icons = {"milestone" + ":⭐", "photo" + ":📷", "note" + ":📝", "vet" + ":🏥"};
        for (int i = 0; i < Math.min(list.size(), 15); i++) {
            PetRecordEntity g = list.get(i);
            String icon = "📝";
            for (String pair : icons) {
                if (pair.startsWith(g.getEventType())) { icon = pair.split(":")[1]; break; }
            }
            sb.append("  ").append(g.getEventDate()).append(" ").append(icon).append(" ").append(g.getContent()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 花费记录 ====================

    public String logExpense(String userId, String petName, String category, BigDecimal amount, String note) {
        petService.getOrCreate(userId, petName);
        PetRecordEntity e = PetRecordEntity.expense(userId, petName, category, LocalDate.now(), amount, note);
        recordMapper.insert(e);
        return "💰 " + petName + " " + categoryName(category) + " " + amount + "元已记录";
    }

    public String queryExpenseSummary(String userId, String petName) {
        String from = LocalDate.now().minusDays(30).format(DF);
        List<Map<String, Object>> sums = recordMapper.sumByName(userId, petName, PetRecordEntity.TYPE_EXPENSE, from);
        List<PetRecordEntity> list = recordMapper.findByUserAndPet(userId, petName, PetRecordEntity.TYPE_EXPENSE);

        if (list.isEmpty()) return "💰 " + petName + " 暂无花费记录。";

        BigDecimal total = sums.stream()
                .map(m -> (BigDecimal) m.get("total")).reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder("💰 " + petName + " 近30天花费：\n\n");
        for (Map<String, Object> m : sums) {
            sb.append("  ").append(categoryName((String) m.get("name")))
                    .append(": ").append(m.get("total")).append("元\n");
        }
        sb.append("  ──────\n  合计: ").append(total).append("元\n");

        // 最近5条
        sb.append("\n📋 最近记录：\n");
        for (int i = 0; i < Math.min(list.size(), 5); i++) {
            PetRecordEntity e = list.get(i);
            sb.append("  ").append(e.getExpenseDate()).append(" ")
                    .append(categoryName(e.getCategory())).append(" ").append(e.getAmount()).append("元");
            if (e.getNote() != null) sb.append(" (").append(e.getNote()).append(")");
            sb.append("\n");
        }
        return sb.toString();
    }

    private String categoryName(String cat) {
        return switch (cat) {
            case "food" -> "🍖食物";
            case "medical" -> "🏥医疗";
            case "grooming" -> "💇美容";
            case "toy" -> "🎾玩具";
            default -> "📦其他";
        };
    }

    // ==================== 症状日记 ====================

    public String logSymptom(String userId, String petName, String symptom, String severity, String note) {
        petService.getOrCreate(userId, petName);
        PetRecordEntity s = PetRecordEntity.symptom(userId, petName, symptom, LocalDate.now(),
                note, severity != null ? severity : "中等");
        recordMapper.insert(s);
        return "📋 " + petName + " 症状已记录: " + symptom;
    }

    public String querySymptomTimeline(String userId, String petName) {
        List<PetRecordEntity> list = recordMapper.findByUserAndPet(userId, petName, PetRecordEntity.TYPE_SYMPTOM);
        if (list.isEmpty()) return "📋 " + petName + " 暂无症状记录。";

        StringBuilder sb = new StringBuilder("📋 " + petName + " 症状时间线：\n\n");
        for (int i = 0; i < Math.min(list.size(), 20); i++) {
            PetRecordEntity s = list.get(i);
            String sev = "轻".equals(s.getSeverity()) ? "🟢" : "重".equals(s.getSeverity()) ? "🔴" : "🟡";
            sb.append("  ").append(s.getSymptomDate()).append(" ").append(sev).append(" ").append(s.getSymptom());
            if (s.getNote() != null) sb.append(" — ").append(s.getNote());
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== 健康评分 ====================

    public String checkPetHealth(String userId, String petName) {
        petService.getOrCreate(userId, petName);
        int score = 100;
        List<String> deductions = new ArrayList<>();

        // 1. 体重偏离
        PetRecordEntity latestWeight = recordMapper.findLatest(userId, petName, PetRecordEntity.TYPE_WEIGHT);
        if (latestWeight == null) {
            score -= 10;
            deductions.add("无体重记录 (-10)");
        } else {
            long daysSince = ChronoUnit.DAYS.between(latestWeight.getRecordDate(), LocalDate.now());
            if (daysSince > 30) {
                score -= 5;
                deductions.add("超过30天未记录体重 (-5)");
            }
        }

        // 2. 疫苗状态
        List<PetRecordEntity> vaccs = recordMapper.findByUserAndPet(userId, petName, PetRecordEntity.TYPE_VACCINE);
        LocalDate today = LocalDate.now();
        boolean hasOverdueVacc = false;
        if (vaccs.isEmpty()) {
            score -= 10;
            deductions.add("无疫苗记录 (-10)");
        } else {
            Set<String> seen = new HashSet<>();
            for (PetRecordEntity v : vaccs) {
                if (seen.add(v.getVaccineName()) && v.getNextDate().isBefore(today)) {
                    hasOverdueVacc = true;
                }
            }
            if (hasOverdueVacc) { score -= 15; deductions.add("有疫苗已过期 (-15)"); }
        }

        // 3. 驱虫状态
        List<PetRecordEntity> dews = recordMapper.findByUserAndPet(userId, petName, PetRecordEntity.TYPE_DEWORM);
        if (dews.isEmpty()) {
            score -= 10;
            deductions.add("无驱虫记录 (-10)");
        } else {
            boolean overdue = false;
            Set<String> seen = new HashSet<>();
            for (PetRecordEntity d : dews) {
                if (seen.add(d.getDewormType()) && d.getNextDate().isBefore(today)) {
                    overdue = true;
                }
            }
            if (overdue) { score -= 10; deductions.add("驱虫已过期 (-10)"); }
        }

        // 4. 体重趋势
        if (latestWeight != null) {
            PetRecordEntity prev = getPreviousWeight(userId, petName);
            if (prev != null) {
                double diff = latestWeight.getWeightKg() - prev.getWeightKg();
                if (Math.abs(diff) > 2) {
                    score -= 5;
                    deductions.add("体重波动较大 (±" + String.format("%.1f", Math.abs(diff)) + "kg) (-5)");
                }
            }
        }

        String grade = score >= 90 ? "⭐ 优秀" : score >= 75 ? "👍 良好" : score >= 60 ? "⚠️ 一般" : "🔴 需关注";
        StringBuilder sb = new StringBuilder();
        sb.append("🏥 ").append(petName).append(" 健康评分: ").append(score).append("/100 ").append(grade).append("\n\n");
        if (!deductions.isEmpty()) {
            sb.append("扣分明细：\n");
            deductions.forEach(d -> sb.append("  ").append(d).append("\n"));
        }
        if (score < 90) {
            sb.append("\n💡 改善建议：\n");
            if (vaccs.isEmpty()) sb.append("  • 建议尽快接种核心疫苗\n");
            if (hasOverdueVacc) sb.append("  • 有疫苗过期，请尽快补种\n");
            if (dews.isEmpty()) sb.append("  • 建议建立驱虫计划\n");
            if (latestWeight == null) sb.append("  • 开始记录体重，监测健康变化\n");
        }
        return sb.toString();
    }

    // ==================== 便便分析 ====================
    // 复用 PetDiagnoseService 的图片诊断链路，只是 prompt 不同
    // 入口在 com.demo.agent.PetAgentTool.analyzeStool() 中
}
