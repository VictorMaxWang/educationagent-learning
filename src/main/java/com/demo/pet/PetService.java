package com.demo.pet;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;

/**
 * 宠物档案服务 — CRUD + 病史管理
 */
@Slf4j
@Service
public class PetService {

    @Resource
    private PetMapper petMapper;

    /** 登记新宠物 */
    public PetEntity register(String userId, String petName, String species, String breed,
                              String gender, String birthDate, Double weightKg, String features) {
        PetEntity existing = petMapper.findByUserIdAndName(userId, petName);
        if (existing != null) {
            throw new IllegalArgumentException("宠物「" + petName + "」已存在，请使用其他名字或先删除");
        }

        PetEntity pet = new PetEntity(userId, petName);
        pet.setSpecies(species);
        pet.setBreed(breed);
        pet.setGender(gender);
        pet.setWeightKg(weightKg);
        pet.setFeatures(features);

        if (birthDate != null && !birthDate.isBlank()) {
            try {
                pet.setBirthDate(LocalDate.parse(birthDate));
            } catch (Exception e) {
                log.warn("[{}] 出生日期解析失败: {}", userId, birthDate);
            }
        }

        petMapper.insert(pet);
        log.info("[{}] 登记宠物: {} ({}/{})", userId, petName, species, breed);
        return pet;
    }

    /** 查询单个宠物 */
    public PetEntity getPet(String userId, String petName) {
        return petMapper.findByUserIdAndName(userId, petName);
    }

    /** 列出用户所有宠物 */
    public List<PetEntity> listPets(String userId) {
        return petMapper.findByUserId(userId);
    }

    /** 按 ID 查询单个宠物（校验归属用户，防越权） */
    public PetEntity getById(Long id, String userId) {
        PetEntity pet = petMapper.selectById(id);
        if (pet != null && userId != null && userId.equals(pet.getUserId())) {
            return pet;
        }
        return null;
    }

    /** 更新宠物信息 */
    public PetEntity update(String userId, String petName, String field, String value) {
        PetEntity pet = petMapper.findByUserIdAndName(userId, petName);
        if (pet == null) {
            throw new IllegalArgumentException("宠物「" + petName + "」不存在，请先登记");
        }

        switch (field) {
            case "species", "种类" -> pet.setSpecies(value);
            case "breed", "品种" -> pet.setBreed(value);
            case "gender", "性别" -> pet.setGender(value);
            case "weightKg", "体重" -> {
                try { pet.setWeightKg(Double.parseDouble(value)); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("体重格式不正确"); }
            }
            case "birthDate", "出生日期" -> {
                try { pet.setBirthDate(LocalDate.parse(value)); }
                catch (Exception e) { throw new IllegalArgumentException("日期格式不正确，请用 yyyy-MM-dd"); }
            }
            case "features", "特征" -> pet.setFeatures(value);
            default -> throw new IllegalArgumentException("不支持的字段: " + field + "，支持: species/breed/gender/weightKg/birthDate/features");
        }

        petMapper.updateById(pet);
        log.info("[{}] 更新宠物 {}: {} = {}", userId, petName, field, value);
        return pet;
    }

    /** 删除宠物 */
    public void delete(String userId, String petName) {
        PetEntity pet = petMapper.findByUserIdAndName(userId, petName);
        if (pet == null) {
            throw new IllegalArgumentException("宠物「" + petName + "」不存在");
        }
        petMapper.deleteById(pet.getId());
        log.info("[{}] 删除宠物: {}", userId, petName);
    }

    /** 追加病史到 pets.illnesses JSON 数组 */
    public void appendIllness(String userId, String petName, String illnessName, String note) {
        PetEntity pet = petMapper.findByUserIdAndName(userId, petName);
        if (pet == null) return;

        JSONArray illnesses;
        String existing = pet.getIllnesses();
        if (existing == null || existing.isBlank()) {
            illnesses = new JSONArray();
        } else {
            try {
                illnesses = JSON.parseArray(existing);
            } catch (Exception e) {
                illnesses = new JSONArray();
            }
        }

        JSONObject entry = new JSONObject();
        entry.put("date", LocalDate.now().toString());
        entry.put("name", illnessName);
        entry.put("note", note != null ? note : "");
        illnesses.add(entry);

        pet.setIllnesses(illnesses.toJSONString());
        petMapper.updateById(pet);
    }

    /** 获取或创建宠物（不存在则自动创建基础档案） */
    public PetEntity getOrCreate(String userId, String petName) {
        PetEntity pet = petMapper.findByUserIdAndName(userId, petName);
        if (pet == null) {
            pet = new PetEntity(userId, petName);
            petMapper.insert(pet);
            log.info("[{}] 自动创建宠物档案: {}", userId, petName);
        }
        return pet;
    }

    /** 格式化宠物档案为可读文本 */
    public String formatPet(PetEntity pet) {
        StringBuilder sb = new StringBuilder();
        sb.append("🐾 ").append(pet.getPetName()).append("\n");
        if (pet.getSpecies() != null) sb.append("  种类: ").append(pet.getSpecies()).append("\n");
        if (pet.getBreed() != null) sb.append("  品种: ").append(pet.getBreed()).append("\n");
        if (pet.getGender() != null) sb.append("  性别: ").append(pet.getGender()).append("\n");
        if (pet.getBirthDate() != null) {
            sb.append("  出生: ").append(pet.getBirthDate());
            Period age = Period.between(pet.getBirthDate(), LocalDate.now());
            sb.append(" (").append(age.getYears()).append("岁").append(age.getMonths()).append("个月)\n");
        }
        if (pet.getWeightKg() != null) sb.append("  体重: ").append(pet.getWeightKg()).append("kg\n");
        if (pet.getFeatures() != null && !pet.getFeatures().isBlank())
            sb.append("  特征: ").append(pet.getFeatures()).append("\n");
        if (pet.getIllnesses() != null && !pet.getIllnesses().isBlank()) {
            sb.append("  病史: ");
            try {
                JSONArray arr = JSON.parseArray(pet.getIllnesses());
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    sb.append(obj.getString("date")).append(" ").append(obj.getString("name"));
                    if (i < arr.size() - 1) sb.append("；");
                }
            } catch (Exception e) {
                sb.append(pet.getIllnesses());
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
