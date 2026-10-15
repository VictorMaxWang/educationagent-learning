package com.demo.pet.controller;
import com.demo.pet.PetDiagnoseService;
import com.demo.pet.PetDiagnosisEntity;
import com.demo.pet.PetEntity;
import com.demo.pet.PetHealthService;
import com.demo.pet.PetKnowledgeService;
import com.demo.pet.PetScheduleEntity;
import com.demo.pet.PetScheduleService;
import com.demo.pet.PetService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 宠物模块 REST API（桌面端）
 * 所有端点集中在 /api/pets 下，DTO 为内部静态类
 */
@Slf4j
@RestController
@RequestMapping("/api/pets")
public class PetController {

    @Resource private PetService petService;
    @Resource private PetDiagnoseService petDiagnoseService;
    @Resource private PetScheduleService petScheduleService;
    @Resource private PetKnowledgeService petKnowledgeService;
    @Resource private PetHealthService petHealthService;

    private static final String IMAGE_DIR = "Data/pet_images";

    // ==================== 宠物档案 ====================

    @PostMapping
    public ApiResponse<PetVO> register(@RequestBody RegisterPetRequest req) {
        try {
            PetEntity pet = petService.register(req.userId, req.petName, req.species,
                    req.breed, req.gender, req.birthDate, req.weightKg, req.features);
            return ApiResponse.ok(toVO(pet));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<List<PetVO>> list(@RequestParam String userId,
                                          @RequestParam(required = false) String name) {
        if (name != null && !name.isBlank()) {
            PetEntity pet = petService.getPet(userId, name);
            if (pet == null) return ApiResponse.error("宠物不存在");
            return ApiResponse.ok(List.of(toVO(pet)));
        }
        List<PetEntity> pets = petService.listPets(userId);
        return ApiResponse.ok(pets.stream().map(this::toVO).collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public ApiResponse<PetVO> getById(@PathVariable Long id,
                                       @RequestParam String userId) {
        PetEntity pet = petService.getById(id, userId);
        if (pet == null) return ApiResponse.error("宠物不存在");
        return ApiResponse.ok(toVO(pet));
    }

    @PutMapping("/{id}")
    public ApiResponse<PetVO> update(@PathVariable Long id,
                                      @RequestBody UpdatePetRequest req) {
        try {
            PetEntity found = petService.getById(id, req.userId);
            if (found == null) return ApiResponse.error("宠物不存在");

            PetEntity updated = petService.update(req.userId, found.getPetName(),
                    req.field, req.value);
            return ApiResponse.ok(toVO(updated));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id,
                                     @RequestParam String userId) {
        try {
            PetEntity found = petService.getById(id, userId);
            if (found == null) return ApiResponse.error("宠物不存在");
            petService.delete(userId, found.getPetName());
            return ApiResponse.ok(null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 智能诊断 ====================

    @PostMapping(value = "/diagnose", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DiagnosisVO> diagnose(
            @RequestParam("image") MultipartFile image,
            @RequestParam String userId,
            @RequestParam String petName,
            @RequestParam(required = false, defaultValue = "") String concern) {
        try {
            // 保存图片
            byte[] bytes = image.getBytes();
            String imagePath = saveImage(userId, bytes);

            // 调用诊断
            String rawText = petDiagnoseService.diagnoseByImageBytes(
                    userId, petName, bytes, concern, imagePath);

            // 构建 VO
            PetDiagnoseService.DiagnosisResult result = parseFromRaw(rawText);
            DiagnosisVO vo = new DiagnosisVO();
            vo.petName = petName;
            vo.diagnosis = result.diagnosis();
            vo.suggestions = result.suggestions();
            vo.keywords = result.keywords();
            vo.rawText = rawText;
            vo.imageUrl = imagePath;
            return ApiResponse.ok(vo);
        } catch (IOException e) {
            return ApiResponse.error("图片处理失败: " + e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("诊断失败: " + e.getMessage());
        }
    }

    @PostMapping("/symptom-check")
    public ApiResponse<DiagnosisVO> checkSymptom(@RequestBody SymptomCheckRequest req) {
        try {
            String rawText = petDiagnoseService.checkSymptom(req.userId, req.petName, req.symptoms);
            PetDiagnoseService.DiagnosisResult result = parseFromRaw(rawText);
            DiagnosisVO vo = new DiagnosisVO();
            vo.petName = req.petName;
            vo.diagnosis = result.diagnosis();
            vo.suggestions = result.suggestions();
            vo.keywords = result.keywords();
            vo.rawText = rawText;
            return ApiResponse.ok(vo);
        } catch (Exception e) {
            return ApiResponse.error("症状分析失败: " + e.getMessage());
        }
    }

    @GetMapping("/diagnoses")
    public ApiResponse<List<DiagnosisVO>> getDiagnoses(
            @RequestParam String userId,
            @RequestParam(required = false) String petName) {
        List<PetDiagnosisEntity> records = petDiagnoseService.getHistory(userId, petName);
        List<DiagnosisVO> vos = records.stream().map(r -> {
            DiagnosisVO vo = new DiagnosisVO();
            vo.id = r.getId();
            vo.petName = r.getPetName();
            vo.diagnosis = r.getDiagnosis();
            vo.suggestions = r.getSuggestions();
            vo.keywords = r.getKeywords();
            vo.rawText = r.getAiRawText();
            vo.imageUrl = r.getImageUrl();
            vo.createdAt = r.getCreatedAt() != null ?
                    r.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : null;
            return vo;
        }).collect(Collectors.toList());
        return ApiResponse.ok(vos);
    }

    // ==================== 定时推送 ====================

    @PostMapping("/schedules")
    public ApiResponse<ScheduleVO> createSchedule(@RequestBody CreateScheduleRequest req) {
        try {
            PetScheduleEntity schedule = petScheduleService.createSchedule(
                    req.userId, req.petName, req.timeExpr, req.action);
            return ApiResponse.ok(toScheduleVO(schedule));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @GetMapping("/schedules")
    public ApiResponse<List<ScheduleVO>> getSchedules(
            @RequestParam String userId,
            @RequestParam(required = false) String petName) {
        List<PetScheduleEntity> schedules = petScheduleService.listSchedules(userId, petName);
        return ApiResponse.ok(schedules.stream().map(this::toScheduleVO).collect(Collectors.toList()));
    }

    @DeleteMapping("/schedules/{scheduleId}")
    public ApiResponse<Void> deleteSchedule(@PathVariable Long scheduleId) {
        try {
            petScheduleService.cancelSchedule(scheduleId);
            return ApiResponse.ok(null);
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    @PutMapping("/schedules/{scheduleId}")
    public ApiResponse<ScheduleVO> toggleSchedule(@PathVariable Long scheduleId,
                                                   @RequestBody ToggleScheduleRequest req) {
        try {
            PetScheduleEntity schedule = petScheduleService.toggleSchedule(scheduleId, req.enabled);
            return ApiResponse.ok(toScheduleVO(schedule));
        } catch (Exception e) {
            return ApiResponse.error(e.getMessage());
        }
    }

    // ==================== 知识查询 ====================

    @GetMapping("/toxic-foods")
    public ApiResponse<ToxicFoodVO> checkToxicFood(@RequestParam String species,
                                                    @RequestParam String food) {
        var result = petKnowledgeService.checkToxicFood(species, food);
        if (result != null) {
            ToxicFoodVO vo = new ToxicFoodVO();
            vo.foodName = result.foodName();
            vo.toxicityLevel = result.toxicityLevel();
            vo.symptoms = result.symptoms();
            vo.explanation = result.explanation();
            vo.isSafe = false;
            return ApiResponse.ok(vo);
        }
        // 未匹配则返回安全提示
        ToxicFoodVO vo = new ToxicFoodVO();
        vo.foodName = food;
        vo.toxicityLevel = "未收录";
        vo.isSafe = true;
        vo.explanation = "未在本地有毒食物库中找到，建议进一步核实或咨询兽医。";
        return ApiResponse.ok(vo);
    }

    @GetMapping("/age-calc")
    public ApiResponse<AgeCalcVO> calcAge(@RequestParam String species,
                                           @RequestParam(required = false) String size,
                                           @RequestParam Double age) {
        PetKnowledgeService.AgeResult result = petKnowledgeService.calcAge(species, size, age);
        AgeCalcVO vo = new AgeCalcVO();
        vo.petAge = result.petAge();
        vo.humanAge = result.humanAge();
        vo.species = result.species();
        vo.formula = result.formula();
        return ApiResponse.ok(vo);
    }

    // ==================== 体重管理 ====================

    @PostMapping("/weights")
    public ApiResponse<Void> logWeight(@RequestBody WeightRequest req) {
        String result = petHealthService.logWeight(req.userId, req.petName, req.weightKg, req.note);
        return ApiResponse.ok(null);
    }

    @GetMapping("/weights")
    public ApiResponse<String> getWeightHistory(@RequestParam String userId, @RequestParam String petName) {
        return ApiResponse.ok(petHealthService.queryWeightHistory(userId, petName));
    }

    // ==================== 疫苗管理 ====================

    @PostMapping("/vaccines")
    public ApiResponse<Void> logVaccine(@RequestBody VaccRequest req) {
        petHealthService.logVaccine(req.userId, req.petName, req.vaccineName);
        return ApiResponse.ok(null);
    }

    @GetMapping("/vaccines")
    public ApiResponse<String> getVaccines(@RequestParam String userId, @RequestParam String petName) {
        return ApiResponse.ok(petHealthService.queryVaccineStatus(userId, petName));
    }

    // ==================== 驱虫管理 ====================

    @PostMapping("/dewormings")
    public ApiResponse<Void> logDeworming(@RequestBody DewormRequest req) {
        petHealthService.logDeworming(req.userId, req.petName, req.dewormType, req.drugName);
        return ApiResponse.ok(null);
    }

    @GetMapping("/dewormings")
    public ApiResponse<String> getDewormings(@RequestParam String userId, @RequestParam String petName) {
        return ApiResponse.ok(petHealthService.queryDewormingStatus(userId, petName));
    }

    // ==================== 用药管理 ====================

    @PostMapping("/medications")
    public ApiResponse<Void> logMedication(@RequestBody MedRequest req) {
        petHealthService.logMedication(req.userId, req.petName, req.drugName,
                req.startDate, req.endDate, req.timesPerDay, req.dosage);
        return ApiResponse.ok(null);
    }

    @GetMapping("/medications")
    public ApiResponse<String> getMedications(@RequestParam String userId, @RequestParam String petName) {
        return ApiResponse.ok(petHealthService.queryMedications(userId, petName));
    }

    // ==================== 成长日记 ====================

    @PostMapping("/growth")
    public ApiResponse<Void> logGrowth(@RequestBody GrowthRequest req) {
        petHealthService.logGrowth(req.userId, req.petName, req.eventType, req.content);
        return ApiResponse.ok(null);
    }

    @GetMapping("/growth")
    public ApiResponse<String> getGrowthDiary(@RequestParam String userId, @RequestParam String petName) {
        return ApiResponse.ok(petHealthService.queryGrowthDiary(userId, petName));
    }

    // ==================== 花费管理 ====================

    @PostMapping("/expenses")
    public ApiResponse<Void> logExpense(@RequestBody ExpenseRequest req) {
        petHealthService.logExpense(req.userId, req.petName, req.category, req.amount, req.note);
        return ApiResponse.ok(null);
    }

    @GetMapping("/expenses")
    public ApiResponse<String> getExpenseSummary(@RequestParam String userId, @RequestParam String petName) {
        return ApiResponse.ok(petHealthService.queryExpenseSummary(userId, petName));
    }

    // ==================== 症状日记 ====================

    @PostMapping("/symptoms")
    public ApiResponse<Void> logSymptom(@RequestBody SymptomRequest req) {
        petHealthService.logSymptom(req.userId, req.petName, req.symptom, req.severity, req.note);
        return ApiResponse.ok(null);
    }

    @GetMapping("/symptoms")
    public ApiResponse<String> getSymptomTimeline(@RequestParam String userId, @RequestParam String petName) {
        return ApiResponse.ok(petHealthService.querySymptomTimeline(userId, petName));
    }

    // ==================== 健康评分 ====================

    @GetMapping("/health-score")
    public ApiResponse<String> getHealthScore(@RequestParam String userId, @RequestParam String petName) {
        return ApiResponse.ok(petHealthService.checkPetHealth(userId, petName));
    }

    // ==================== 内部方法 ====================

    private PetVO toVO(PetEntity pet) {
        PetVO vo = new PetVO();
        vo.id = pet.getId();
        vo.userId = pet.getUserId();
        vo.petName = pet.getPetName();
        vo.species = pet.getSpecies();
        vo.breed = pet.getBreed();
        vo.gender = pet.getGender();
        vo.birthDate = pet.getBirthDate() != null ? pet.getBirthDate().toString() : null;
        vo.weightKg = pet.getWeightKg();
        vo.photoUrl = pet.getPhotoUrl();
        vo.illnesses = pet.getIllnesses();
        vo.features = pet.getFeatures();
        vo.createdAt = pet.getCreatedAt() != null ?
                pet.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : null;
        return vo;
    }

    private ScheduleVO toScheduleVO(PetScheduleEntity s) {
        ScheduleVO vo = new ScheduleVO();
        vo.id = s.getId();
        vo.userId = s.getUserId();
        vo.petName = s.getPetName();
        vo.timeExpr = s.getTimeExpr();
        vo.cronExpr = s.getCronExpr();
        vo.action = s.getAction();
        vo.enabled = s.getEnabled() == 1;
        vo.createdAt = s.getCreatedAt() != null ?
                s.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : null;
        return vo;
    }

    private String saveImage(String userId, byte[] bytes) throws IOException {
        String dir = IMAGE_DIR + "/" + userId;
        Files.createDirectories(Paths.get(dir));
        String filename = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + UUID.randomUUID().toString().substring(0, 6) + ".jpg";
        Path filePath = Paths.get(dir, filename);
        Files.write(filePath, bytes);
        String relativePath = dir + "/" + filename;
        log.info("🖼️ 保存诊断图片: {}", relativePath);
        return relativePath;
    }

    private PetDiagnoseService.DiagnosisResult parseFromRaw(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return new PetDiagnoseService.DiagnosisResult("", "", "");
        }
        // 简单解析（与 PetDiagnoseService.parseDiagnosis 逻辑一致）
        String diagnosis = extractSection(rawText, "诊断结论");
        String suggestions = extractSection(rawText, "处理建议");
        if (suggestions.isEmpty()) suggestions = extractSection(rawText, "详细分析");

        java.util.ArrayList<String> kw = new java.util.ArrayList<>();
        String[] terms = {"皮炎", "湿疹", "猫癣", "过敏", "耳螨", "寄生虫", "感染", "骨折",
                "腹泻", "呕吐", "感冒", "犬瘟", "细小", "猫瘟", "肾衰竭", "尿结石", "心脏病",
                "关节炎", "肥胖", "营养不良", "中毒", "焦虑", "肿瘤", "结膜炎", "口腔炎"};
        for (String t : terms) {
            if (rawText.contains(t)) kw.add(t);
        }
        return new PetDiagnoseService.DiagnosisResult(diagnosis, suggestions, String.join(",", kw));
    }

    private String extractSection(String text, String sectionName) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "【" + sectionName + "】\\s*([\\s\\S]*?)(?=【|$)");
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) {
            return m.group(1).trim().replaceAll("\\n{3,}", "\n\n");
        }
        return "";
    }

    // ==================== DTO 内部静态类 ====================

    public static class ApiResponse<T> {
        public int code;
        public String message;
        public T data;

        public static <T> ApiResponse<T> ok(T data) {
            ApiResponse<T> r = new ApiResponse<>();
            r.code = 200;
            r.message = "success";
            r.data = data;
            return r;
        }

        public static <T> ApiResponse<T> error(String msg) {
            ApiResponse<T> r = new ApiResponse<>();
            r.code = 400;
            r.message = msg;
            r.data = null;
            return r;
        }
    }

    // ---- 请求 DTO ----

    public static class RegisterPetRequest {
        public String userId;
        public String petName;
        public String species;
        public String breed;
        public String gender;
        public String birthDate;
        public Double weightKg;
        public String features;
    }

    public static class UpdatePetRequest {
        public String userId;
        public String field;
        public String value;
    }

    public static class SymptomCheckRequest {
        public String userId;
        public String petName;
        public String symptoms;
    }

    public static class CreateScheduleRequest {
        public String userId;
        public String petName;
        public String timeExpr;
        public String action;
    }

    public static class ToggleScheduleRequest {
        public boolean enabled;
    }

    // ---- 响应 VO ----

    public static class PetVO {
        public Long id;
        public String userId;
        public String petName;
        public String species;
        public String breed;
        public String gender;
        public String birthDate;
        public Double weightKg;
        public String photoUrl;
        public String illnesses;
        public String features;
        public String createdAt;
    }

    public static class DiagnosisVO {
        public Long id;
        public String petName;
        public String diagnosis;
        public String suggestions;
        public String keywords;
        public String rawText;
        public String imageUrl;
        public String createdAt;
    }

    public static class ScheduleVO {
        public Long id;
        public String userId;
        public String petName;
        public String timeExpr;
        public String cronExpr;
        public String action;
        public boolean enabled;
        public String createdAt;
    }

    public static class ToxicFoodVO {
        public String foodName;
        public String toxicityLevel;
        public String symptoms;
        public String explanation;
        public boolean isSafe;
    }

    public static class AgeCalcVO {
        public double petAge;
        public int humanAge;
        public String species;
        public String formula;
    }

    // ---- 健康/成长请求 DTO ----

    public static class WeightRequest {
        public String userId; public String petName; public Double weightKg; public String note;
    }
    public static class VaccRequest {
        public String userId; public String petName; public String vaccineName;
    }
    public static class DewormRequest {
        public String userId; public String petName; public String dewormType; public String drugName;
    }
    public static class MedRequest {
        public String userId; public String petName; public String drugName;
        public String startDate; public String endDate; public int timesPerDay; public String dosage;
    }
    public static class GrowthRequest {
        public String userId; public String petName; public String eventType; public String content;
    }
    public static class ExpenseRequest {
        public String userId; public String petName; public String category; public BigDecimal amount; public String note;
    }
    public static class SymptomRequest {
        public String userId; public String petName; public String symptom; public String severity; public String note;
    }
}
