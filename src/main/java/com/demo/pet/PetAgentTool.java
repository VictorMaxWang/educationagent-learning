package com.demo.pet;

import com.demo.skill.annotation.RouterMatch;
import com.demo.util.VoiceContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 宠物模块 Agent 工具 — 将宠物诊断/健康/知识等能力暴露给 LLM，使微信端可自然语言交互。
 * <p>
 * 所有方法通过 {@code VoiceContext.getUserId()} 获取当前微信用户 ID，
 * 无需调用方传入 userId，保持与现有 @Tool 模式一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PetAgentTool {

    private final PetDiagnoseService diagnoseService;
    private final PetHealthService healthService;
    private final PetKnowledgeService knowledgeService;
    private final PetScheduleService scheduleService;
    private final PetService petService;

    // ==================== 智能诊断 ====================

    @Tool("宠物症状分析诊断。当用户描述宠物症状（如呕吐、拉肚子、不吃东西、皮肤红肿等）时调用此工具进行AI综合分析。"
            + "注意：这是纯文字症状分析，如果用户发了宠物照片，照片已经由预处理器转为文字描述，同样走此工具")
    public String checkPetSymptom(
            @P("宠物名字") String petName,
            @P("症状描述，如：呕吐、腹泻、没精神、皮肤红肿") String symptoms) {
        String userId = VoiceContext.getUserId();
        log.info("🐾 [{}] 宠物症状诊断: {} → {}", userId, petName, symptoms);
        return diagnoseService.checkSymptom(userId, petName, symptoms);
    }

    @Tool("查看宠物的历史诊断记录")
    public String getPetDiagnosisHistory(
            @P("宠物名字（可选，不填则查全部宠物）") String petName) {
        String userId = VoiceContext.getUserId();
        String name = (petName == null || petName.isBlank()) ? null : petName;
        return diagnoseService.formatHistory(userId, name);
    }

    // ==================== 有毒食物 ====================

    @Tool("查询某种食物对宠物是否有毒。当用户问'xx能给狗/猫吃吗'、'狗能吃xx吗'时必须调用此工具")
    @RouterMatch(
        patterns = {
            "(?<food>.+?)能给?(?<species>狗|猫|猫狗|狗狗)吃",
            "(?<species>狗|猫|狗狗|猫咪)能(?:不能)?吃(?<food>.+)",
            "(?<food>.+?)(?:对|给)(?<species>狗|猫|狗狗|猫咪)有毒",
        },
        confidence = 0.85,
        priority = 8
    )
    public String checkToxicFood(
            @P("宠物种类，如：狗、猫") String species,
            @P("食物名称，如：巧克力、葡萄、洋葱") String food) {
        log.info("🐾 有毒食物查询: {} → {}", species, food);
        var result = knowledgeService.checkToxicFood(species, food);
        if (result != null) {
            return "🚫【" + result.foodName() + "】对" + species + "："
                    + result.toxicityLevel() + "\n"
                    + "症状：" + result.symptoms() + "\n"
                    + "说明：" + result.explanation();
        }
        return "✅ 未在有毒食物库中找到「" + food + "」对" + species + "的毒性记录。"
                + "建议进一步核实或咨询兽医，不同品种/体型耐受度可能不同。";
    }

    // ==================== 体重管理 ====================

    @Tool("记录宠物体重。当用户说'xx今天X斤/X公斤'时调用此工具")
    public String logPetWeight(
            @P("宠物名字") String petName,
            @P("体重（公斤），用户说斤时需除以2") double weightKg) {
        String userId = VoiceContext.getUserId();
        return healthService.logWeight(userId, petName, weightKg, null);
    }

    @Tool("查看宠物体重变化趋势和历史记录")
    public String getPetWeightHistory(
            @P("宠物名字") String petName) {
        String userId = VoiceContext.getUserId();
        return healthService.queryWeightHistory(userId, petName);
    }

    // ==================== 疫苗管理 ====================

    @Tool("记录宠物疫苗接种。当用户说'xx打了疫苗/接种了xx疫苗'时调用")
    public String logPetVaccine(
            @P("宠物名字") String petName,
            @P("疫苗名称，如：狂犬、猫三联、犬瘟") String vaccineName) {
        String userId = VoiceContext.getUserId();
        return healthService.logVaccine(userId, petName, vaccineName);
    }

    @Tool("查看宠物疫苗状态，包括已接种疫苗、有效期、是否需要补种")
    public String getPetVaccines(
            @P("宠物名字") String petName) {
        String userId = VoiceContext.getUserId();
        return healthService.queryVaccineStatus(userId, petName);
    }

    // ==================== 驱虫管理 ====================

    @Tool("记录宠物驱虫。当用户说'xx做了驱虫/驱虫了'时调用。驱虫类型填'体内'或'体外'")
    public String logPetDeworming(
            @P("宠物名字") String petName,
            @P("驱虫类型：体内或体外") String dewormType) {
        String userId = VoiceContext.getUserId();
        return healthService.logDeworming(userId, petName,
                dewormType != null && dewormType.contains("体") ? dewormType : "体内",
                null);
    }

    @Tool("查看宠物驱虫状态和下次驱虫日期")
    public String getPetDewormings(
            @P("宠物名字") String petName) {
        String userId = VoiceContext.getUserId();
        return healthService.queryDewormingStatus(userId, petName);
    }

    // ==================== 用药管理 ====================

    @Tool("设置宠物用药提醒。当用户说'xx在吃药/需要喂药'时调用")
    public String logPetMedication(
            @P("宠物名字") String petName,
            @P("药品名称") String drugName,
            @P("开始日期，格式yyyy-MM-dd") String startDate,
            @P("结束日期，格式yyyy-MM-dd") String endDate,
            @P("每天服药次数，默认1次") int timesPerDay) {
        String userId = VoiceContext.getUserId();
        return healthService.logMedication(userId, petName, drugName,
                startDate, endDate, Math.max(timesPerDay, 1), null);
    }

    // ==================== 健康评分 ====================

    @Tool("综合评估宠物健康状况，返回健康评分和改善建议。涵盖体重、疫苗、驱虫等维度")
    public String getPetHealthScore(
            @P("宠物名字") String petName) {
        String userId = VoiceContext.getUserId();
        return healthService.checkPetHealth(userId, petName);
    }

    // ==================== 成长日记 ====================

    @Tool("记录宠物成长日记。当用户分享宠物趣事/里程碑/照片时调用。eventType填：milestone/note/vet/photo")
    public String logPetGrowth(
            @P("宠物名字") String petName,
            @P("事件类型：milestone(里程碑)/note(笔记)/vet(就医)/photo(照片)") String eventType,
            @P("事件内容描述") String content) {
        String userId = VoiceContext.getUserId();
        return healthService.logGrowth(userId, petName, eventType, content);
    }

    @Tool("查看宠物成长日记")
    public String getPetGrowthDiary(
            @P("宠物名字") String petName) {
        String userId = VoiceContext.getUserId();
        return healthService.queryGrowthDiary(userId, petName);
    }

    // ==================== 花费记录 ====================

    @Tool("记录宠物花费。当用户说'给xx买了/花了/看病花了XX元'时调用")
    public String logPetExpense(
            @P("宠物名字") String petName,
            @P("花费类别：food(食物)/medical(医疗)/grooming(美容)/toy(玩具)/other(其他)") String category,
            @P("金额（元）") double amount) {
        String userId = VoiceContext.getUserId();
        return healthService.logExpense(userId, petName,
                category != null ? category : "other",
                BigDecimal.valueOf(amount), null);
    }

    @Tool("查看宠物近期花费统计")
    public String getPetExpenses(
            @P("宠物名字") String petName) {
        String userId = VoiceContext.getUserId();
        return healthService.queryExpenseSummary(userId, petName);
    }

    // ==================== 档案管理 ====================

    @Tool("登记新宠物。当用户第一次提到宠物名字且档案不存在时，引导用户登记。"
            + "如果用户只说了名字没有详细信息，先创建基础档案")
    public String registerPet(
            @P("宠物名字") String petName,
            @P("种类：狗/猫/兔/鸟/其他") String species,
            @P("品种，如：金毛、英短、柯基（可选）") String breed,
            @P("性别：公/母（可选）") String gender,
            @P("出生日期，格式yyyy-MM-dd（可选）") String birthDate,
            @P("体重（公斤）（可选）") Double weightKg) {
        String userId = VoiceContext.getUserId();
        try {
            PetEntity pet = petService.register(userId, petName,
                    species, breed, gender, birthDate, weightKg, null);
            return "✅ 宠物「" + petName + "」已登记\n" + petService.formatPet(pet);
        } catch (IllegalArgumentException e) {
            // 已存在则返回现有档案
            PetEntity existing = petService.getPet(userId, petName);
            if (existing != null) {
                return "📋 宠物「" + petName + "」已存在\n" + petService.formatPet(existing);
            }
            return e.getMessage();
        }
    }

    @Tool("查看宠物完整档案，包括品种、年龄、体重、病史等")
    public String getPetProfile(
            @P("宠物名字") String petName) {
        String userId = VoiceContext.getUserId();
        PetEntity pet = petService.getPet(userId, petName);
        if (pet == null) {
            return "❌ 未找到宠物「" + petName + "」。请先登记：告诉我它的种类和品种即可。";
        }
        return petService.formatPet(pet);
    }

    @Tool("列出当前用户所有已登记的宠物")
    public String listMyPets() {
        String userId = VoiceContext.getUserId();
        List<PetEntity> pets = petService.listPets(userId);
        if (pets.isEmpty()) {
            return "📋 您还没有登记宠物。告诉我宠物名字和种类即可登记。";
        }
        StringBuilder sb = new StringBuilder("🐾 您的宠物：\n\n");
        for (PetEntity pet : pets) {
            sb.append(petService.formatPet(pet)).append("\n");
        }
        return sb.toString();
    }

    @Tool("更新宠物信息。支持修改种类/品种/性别/出生日期/体重/特征")
    public String updatePet(
            @P("宠物名字") String petName,
            @P("要修改的字段：species(种类)/breed(品种)/gender(性别)/birthDate(出生日期)/weightKg(体重)") String field,
            @P("新的值") String value) {
        String userId = VoiceContext.getUserId();
        try {
            PetEntity updated = petService.update(userId, petName, field, value);
            return "✅ " + petName + " 的" + field + "已更新\n" + petService.formatPet(updated);
        } catch (Exception e) {
            return "❌ " + e.getMessage();
        }
    }

    // ==================== 定时提醒 ====================

    @Tool("添加宠物定时提醒。当用户说'每天X点提醒我喂xx/遛xx'时调用。时间用大白话描述即可，会自动解析")
    public String addPetSchedule(
            @P("宠物名字") String petName,
            @P("时间描述，如：每天早上8点、每晚8点半、每周一早上9点") String timeExpr,
            @P("提醒内容，如：喂食、遛狗、吃药") String action) {
        String userId = VoiceContext.getUserId();
        try {
            PetScheduleEntity schedule = scheduleService.createSchedule(userId, petName, timeExpr, action);
            return "✅ 定时提醒已创建：" + petName + " — " + timeExpr + " — " + action
                    + "（cron: " + schedule.getCronExpr() + "）";
        } catch (Exception e) {
            return "❌ " + e.getMessage();
        }
    }

    @Tool("查看宠物的所有定时提醒任务")
    public String listPetSchedules(
            @P("宠物名字（可选）") String petName) {
        String userId = VoiceContext.getUserId();
        return scheduleService.formatSchedules(
                scheduleService.listSchedules(userId,
                        (petName != null && !petName.isBlank()) ? petName : null));
    }

    @Tool("取消宠物定时提醒")
    public String cancelPetSchedule(
            @P("定时任务ID，可通过查看定时任务获得") long scheduleId) {
        try {
            scheduleService.cancelSchedule(scheduleId);
            return "✅ 定时任务已取消（ID: " + scheduleId + "）";
        } catch (Exception e) {
            return "❌ " + e.getMessage();
        }
    }

    // ==================== 年龄换算 ====================

    @Tool("将宠物年龄换算为人类年龄。当用户问'xx相当于人多少岁/xx多大了'时调用")
    @RouterMatch(
        patterns = {
            "(?<petName>.+?)相当于人(?:类)?多少岁",
            "(?<petName>.+?)(?:多大了|几岁了|年龄)",
        },
        confidence = 0.70,
        priority = 5
    )
    public String calcPetAge(
            @P("宠物名字，需要查档案获取年龄；如果用户直接说'3岁的狗'则填具体年龄数字") String petNameOrAge,
            @P("宠物种类，狗/猫") String species) {
        String userId = VoiceContext.getUserId();
        double petAge;
        String breedSize = null;

        // 尝试解析为数字
        try {
            petAge = Double.parseDouble(petNameOrAge.replace("岁", "").trim());
        } catch (NumberFormatException e) {
            // 当作宠物名字，查档案
            PetEntity pet = petService.getPet(userId, petNameOrAge);
            if (pet == null || pet.getBirthDate() == null) {
                return "❌ 需要知道" + petNameOrAge + "的年龄。请直接告诉我，如'3岁的金毛'";
            }
            petAge = java.time.Period.between(pet.getBirthDate(), java.time.LocalDate.now()).getYears()
                    + java.time.Period.between(pet.getBirthDate(), java.time.LocalDate.now()).getMonths() / 12.0;
            if (species == null && pet.getSpecies() != null) species = pet.getSpecies();
            if (breedSize == null && pet.getBreed() != null) breedSize = pet.getBreed();
        }

        PetKnowledgeService.AgeResult result = knowledgeService.calcAge(
                species != null ? species : "狗", breedSize, petAge);
        return petNameOrAge + "（" + species + "，实际" + result.petAge() + "岁）"
                + " ≈ 人类 " + result.humanAge() + " 岁\n"
                + "换算公式：" + result.formula();
    }
}
