package com.demo.pet;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 宠物知识服务 — 有毒食物库 + 年龄换算 + 品种百科引导
 * 有毒食物数据常驻内存，启动时初始化
 */
@Slf4j
@Service
public class PetKnowledgeService {

    /** species -> (foodName -> detail) */
    private final Map<String, Map<String, ToxicFood>> toxicDb = new HashMap<>();

    @PostConstruct
    public void init() {
        initToxicFoods();
        log.info("🐾 宠物知识库初始化完成: {} 条有毒食物记录", countFoods());
    }

    // ==================== 有毒食物 ====================

    public ToxicFood checkToxicFood(String species, String foodName) {
        // 先精确匹配该物种
        Map<String, ToxicFood> foods = toxicDb.get(species.toLowerCase());
        if (foods != null) {
            ToxicFood match = foods.get(foodName);
            if (match != null) return match;
            // 模糊匹配
            for (Map.Entry<String, ToxicFood> e : foods.entrySet()) {
                if (e.getKey().contains(foodName) || foodName.contains(e.getKey())) {
                    return e.getValue();
                }
            }
        }
        // 查 "all" 通用
        Map<String, ToxicFood> allFoods = toxicDb.get("all");
        if (allFoods != null) {
            ToxicFood match = allFoods.get(foodName);
            if (match != null) return match;
        }
        return null; // 未命中，由调用方走 LLM 补充
    }

    // ==================== 年龄换算 ====================

    public AgeResult calcAge(String species, String breedSize, double petAgeYears) {
        int humanAge;
        String formula;

        if ("猫".equals(species) || "cat".equalsIgnoreCase(species)) {
            humanAge = calcCatAge(petAgeYears);
            formula = "猫：第1年≈15岁，第2年≈+9岁，之后每年≈+4岁";
        } else {
            // 狗：按体型区分
            String size = (breedSize != null) ? breedSize : "中型";
            if (size.contains("小")) {
                humanAge = calcDogAge(petAgeYears, 4);
                formula = "小型犬：第1年≈15岁，第2年≈+9岁，之后每年≈+4岁";
            } else if (size.contains("大")) {
                humanAge = calcDogAge(petAgeYears, 6);
                formula = "大型犬：第1年≈15岁，第2年≈+9岁，之后每年≈+6岁";
            } else {
                humanAge = calcDogAge(petAgeYears, 5);
                formula = "中型犬：第1年≈15岁，第2年≈+9岁，之后每年≈+5岁";
            }
        }

        return new AgeResult(petAgeYears, humanAge, species, formula);
    }

    private int calcCatAge(double years) {
        if (years <= 1) return (int) Math.round(years * 15);
        if (years <= 2) return 15 + (int) Math.round((years - 1) * 9);
        return 24 + (int) Math.round((years - 2) * 4);
    }

    private int calcDogAge(double years, int perYear) {
        if (years <= 1) return (int) Math.round(years * 15);
        if (years <= 2) return 15 + (int) Math.round((years - 1) * 9);
        return 24 + (int) Math.round((years - 2) * perYear);
    }

    // ==================== 品种百科 ====================

    /** 品种百科由 LLM 回答，这里只返回引导文本 */
    public String getBreedGuide(String species, String breed) {
        return "请根据你的知识，详细介绍" + species + "品种「" + breed + "」的特点，包括："
                + "外貌特征、性格特点、饲养难度、常见健康问题、适合人群、平均寿命。"
                + "用亲切专业的宠物顾问口吻。";
    }

    // ==================== 数据模型 ====================

    public record ToxicFood(String foodName, String toxicityLevel, String symptoms, String explanation) {}

    public record AgeResult(double petAge, int humanAge, String species, String formula) {}

    // ==================== 初始化数据 ====================

    private void initToxicFoods() {
        // === 狗不能吃的 ===
        Map<String, ToxicFood> dogFoods = new LinkedHashMap<>();
        dogFoods.put("巧克力", new ToxicFood("巧克力", "致命",
                "呕吐、腹泻、心跳加速、癫痫、内出血",
                "巧克力含可可碱和咖啡因，狗无法代谢。黑巧克力毒性最强，少量即可致命。"));
        dogFoods.put("葡萄", new ToxicFood("葡萄", "致命",
                "呕吐、腹泻、嗜睡、急性肾衰竭",
                "葡萄和葡萄干对狗有肾毒性，即使少量也可能导致急性肾衰竭，机制尚不完全明确。"));
        dogFoods.put("洋葱", new ToxicFood("洋葱", "危险",
                "呕吐、腹泻、红细胞破坏、溶血性贫血",
                "洋葱/大葱/蒜/韭菜含硫化物，破坏狗的红细胞，导致溶血性贫血。生熟都有毒。"));
        dogFoods.put("大蒜", new ToxicFood("大蒜", "危险",
                "同洋葱，但毒性稍弱",
                "大蒜含硫代硫酸盐，大量摄入会引起溶血性贫血。比洋葱毒性稍弱但仍需避免。"));
        dogFoods.put("木糖醇", new ToxicFood("木糖醇", "致命",
                "低血糖、呕吐、抽搐、肝衰竭",
                "口香糖/无糖食品常见。极少量即可引起胰岛素急剧释放导致低血糖，大量可致肝衰竭。"));
        dogFoods.put("夏威夷果", new ToxicFood("夏威夷果", "危险",
                "虚弱、呕吐、步态不稳、体温升高",
                "夏威夷果对狗有神经毒性，12小时内出现症状，通常24-48小时恢复但需观察。"));
        dogFoods.put("牛油果", new ToxicFood("牛油果", "中等",
                "呕吐、腹泻、呼吸困难",
                "果肉/果核/叶子含persin。狗的耐受度比猫和鸟高，但大量摄入仍有毒。果核还有窒息风险。"));
        dogFoods.put("酒精", new ToxicFood("酒精", "致命",
                "呕吐、腹泻、呼吸困难、昏迷、死亡",
                "含酒精饮料/食物对狗毒性很大，即使是少量。注意发酵面团、含酒精甜点。"));
        dogFoods.put("咖啡因", new ToxicFood("咖啡因", "危险",
                "心跳加速、烦躁、震颤、癫痫",
                "咖啡/茶/能量饮料含咖啡因，狗无法有效代谢，类似巧克力的可可碱中毒。"));
        dogFoods.put("生酵母面团", new ToxicFood("生酵母面团", "危险",
                "腹胀、乙醇中毒、胃扭转",
                "生面团在胃里发酵产生酒精（乙醇中毒），同时膨胀可引起致命胃扩张-扭转。"));
        dogFoods.put("核桃", new ToxicFood("核桃", "中等",
                "呕吐、腹泻、神经症状",
                "特别是黑核桃/发霉的核桃。霉菌毒素可引起震颤、癫痫。还有肠梗阻风险。"));
        dogFoods.put("骨头", new ToxicFood("骨头", "中等",
                "牙裂、口腔损伤、食道堵塞、肠穿孔",
                "特别是煮熟的禽类骨头（鸡/鸭/鱼）容易碎裂刺穿消化道。生骨头风险较低但不推荐。"));
        dogFoods.put("牛奶", new ToxicFood("牛奶及乳制品", "轻度",
                "腹泻、胀气、腹部不适",
                "大多数成年狗乳糖不耐受，无法消化乳糖。少量奶酪或酸奶耐受度稍好。"));
        dogFoods.put("生鸡蛋", new ToxicFood("生鸡蛋", "轻度",
                "皮肤问题、脱毛、生物素缺乏",
                "生蛋清含抗生物素蛋白，长期喂会影响B族维生素吸收。还有沙门氏菌风险。"));
        dogFoods.put("盐", new ToxicFood("过量盐分", "危险",
                "呕吐、腹泻、震颤、癫痫、钠中毒",
                "高盐食物（腌制品/薯片/咸肉）可引起钠离子中毒，导致脑水肿。严重可致命。"));
        toxicDb.put("狗", dogFoods);
        toxicDb.put("dog", dogFoods);

        // === 猫不能吃的 ===
        Map<String, ToxicFood> catFoods = new LinkedHashMap<>();
        catFoods.put("百合花", new ToxicFood("百合花", "致命",
                "呕吐、食欲不振、嗜睡、急性肾衰竭",
                "百合科植物（复活节百合/虎皮百合/百合花）对猫剧毒，花粉甚至花瓶水都可致肾衰竭。"));
        catFoods.put("巧克力", new ToxicFood("巧克力", "致命",
                "同狗，猫更敏感",
                "猫体型更小，即使少量巧克力也可能中毒。黑巧/烘焙可可最危险。"));
        catFoods.put("洋葱", new ToxicFood("洋葱/大蒜/葱", "危险",
                "溶血性贫血、呕吐、虚弱、呼吸困难",
                "同狗的机制，破坏红细胞。猫比狗更敏感，少量浓缩粉（如洋葱粉）就有风险。"));
        catFoods.put("葡萄", new ToxicFood("葡萄/葡萄干", "致命",
                "同狗，猫亦有肾毒性风险",
                "虽然没有狗那么多案例报道，但猫同样有风险，应避免。"));
        catFoods.put("金枪鱼罐头", new ToxicFood("金枪鱼罐头", "轻度",
                "营养不均衡、汞中毒、维生素E缺乏",
                "长期吃金枪鱼罐头会导致黄脂病。人用金枪鱼缺乏牛磺酸，会导致猫心脏问题。"));
        catFoods.put("生鱼", new ToxicFood("生鱼", "轻度",
                "硫胺素缺乏、神经系统问题",
                "生鱼含硫胺素酶破坏维生素B1，长期吃引起食欲不振、癫痫。还有寄生虫风险。"));
        catFoods.put("牛奶", new ToxicFood("牛奶及乳制品", "轻度",
                "腹泻、腹部不适",
                "成年猫同样乳糖不耐受。幼猫有乳糖酶但断奶后逐渐减少。"));
        catFoods.put("酒精", new ToxicFood("酒精", "致命",
                "呕吐、腹泻、呼吸困难、昏迷",
                "同狗，猫体型更小耐受更低。"));
        catFoods.put("咖啡因", new ToxicFood("咖啡因", "危险",
                "心跳加速、烦躁、震颤",
                "咖啡/茶/可乐/能量饮料。猫对咖啡因特别敏感。"));
        catFoods.put("木糖醇", new ToxicFood("木糖醇", "危险",
                "低血糖、肝损伤",
                "虽然猫的研究不如狗多，但同样应避免。常见于无糖口香糖/花生酱。"));
        catFoods.put("狗粮", new ToxicFood("狗粮", "轻度",
                "牛磺酸缺乏、营养不良",
                "猫是专性肉食动物，需要比狗更高的蛋白质和牛磺酸。长期吃狗粮会导致心脏病和失明。"));
        catFoods.put("肝脏过量", new ToxicFood("过量动物肝脏", "轻度",
                "维生素A中毒、骨骼问题",
                "少量可以，但长期大量喂肝脏会导致维生素A过多症，引起关节僵硬和骨骼畸形。"));
        toxicDb.put("猫", catFoods);
        toxicDb.put("cat", catFoods);

        log.info("🐾 有毒食物库: 狗{}条, 猫{}条", dogFoods.size(), catFoods.size());
    }

    private int countFoods() {
        Set<String> counted = new HashSet<>();
        for (Map<String, ToxicFood> foods : toxicDb.values()) {
            for (ToxicFood f : foods.values()) {
                counted.add(f.foodName());
            }
        }
        return counted.size();
    }
}
