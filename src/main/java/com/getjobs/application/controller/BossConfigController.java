package com.getjobs.application.controller;

import com.getjobs.application.entity.BossConfigEntity;
import com.getjobs.application.entity.BossOptionEntity;
import com.getjobs.application.service.BossService;
import com.getjobs.application.entity.BlacklistEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.CrossOrigin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/boss/config")
public class BossConfigController {

    private final BossService bossService;

    public BossConfigController(BossService bossService) {
        this.bossService = bossService;
    }

    /**
     * 获取所有Boss配置信息（包括所有选项和黑名单）
     */
    @GetMapping
    public Map<String, Object> getAllBossConfig() {
        Map<String, Object> result = new HashMap<>();

        // 获取配置
        BossConfigEntity config = bossService.getFirstConfig();
        if (config == null) {
            config = new BossConfigEntity();
        }

        // 获取所有选项并按类型分组
        Map<String, List<BossOptionEntity>> options = new HashMap<>();
        options.put("city", bossService.getOptionsByType("city"));
        options.put("industry", bossService.getOptionsByType("industry"));
        options.put("experience", bossService.getOptionsByType("experience"));
        options.put("jobType", bossService.getOptionsByType("jobType"));
        options.put("salary", bossService.getOptionsByType("salary"));
        options.put("degree", bossService.getOptionsByType("degree"));
        options.put("scale", bossService.getOptionsByType("scale"));
        options.put("stage", bossService.getOptionsByType("stage"));

        // 获取黑名单列表
        List<BlacklistEntity> blacklist = bossService.getAllBlacklist();

        result.put("config", config);
        result.put("options", options);
        result.put("blacklist", blacklist);

        return result;
    }

    /**
     * 更新Boss配置
     */
  @PutMapping
  public BossConfigEntity updateConfig(@RequestBody BossConfigEntity config) {
        // 关键词标准化：将来自前端的逗号分隔或括号列表统一转换为 JSON 字符串列表
        config.setKeywords(normalizeKeywords(config.getKeywords()));

        // 将前端可能传来的『代码列表』转换并保存成『中文名称列表/值』
        // 城市：网页端现在是逗号分隔的输入框，能表达多城市（如 "深圳,广州"）。
        //   这里统一归一化成『城市中文名的括号列表』（单城市就是该名字，如 "不限"）：
        //     - 接受 code / 中文名 / "深圳,广州" / "[深圳,广州]"（见 normalizeCityToName）
        //     - 「不限」是"全选"，与具体城市混在一起时会被丢掉
        //   空值 = 不改动该字段，避免"前端没带 cityCode 就把城市清空"。
        //   （下游 loadBossConfig 会把中文名转成 code，「不限」再由 Boss.buildSearchUrl
        //    映射成 boss 的全国码 100010000）
        if (config.getCityCode() != null && !config.getCityCode().trim().isEmpty()) {
            config.setCityCode(bossService.normalizeCityToName(config.getCityCode()));
        } else {
            config.setCityCode(null);
        }
        // 排除的城市/省份：与城市相反 —— **空字符串表示"清空排除表"**（用户把内容删掉就是要取消排除），
        // null 才是"本次不改动"。只做去空白与统一分隔符，不做 code 转换（省份名要留给 CityFilter 展开）。
        if (config.getCityExclude() != null) {
            String raw = config.getCityExclude().trim();
            config.setCityExclude(raw.isEmpty()
                    ? ""
                    : bossService.toBracketListString(bossService.parseListString(raw)));
        }
        // 其它多选：保存为中文名称的括号列表
        if (config.getIndustry() != null) {
            java.util.List<String> names = bossService.toNames("industry", bossService.parseListString(config.getIndustry()));
            config.setIndustry(bossService.toBracketListString(names));
        }
        if (config.getExperience() != null) {
            java.util.List<String> names = bossService.toNames("experience", bossService.parseListString(config.getExperience()));
            config.setExperience(bossService.toBracketListString(names));
        }
        if (config.getDegree() != null) {
            java.util.List<String> names = bossService.toNames("degree", bossService.parseListString(config.getDegree()));
            config.setDegree(bossService.toBracketListString(names));
        }
        if (config.getScale() != null) {
            java.util.List<String> names = bossService.toNames("scale", bossService.parseListString(config.getScale()));
            config.setScale(bossService.toBracketListString(names));
        }
        if (config.getStage() != null) {
            java.util.List<String> names = bossService.toNames("stage", bossService.parseListString(config.getStage()));
            config.setStage(bossService.toBracketListString(names));
        }
        if (config.getSalary() != null) {
            java.util.List<String> names = bossService.toNames("salary", bossService.parseListString(config.getSalary()));
            config.setSalary(bossService.toBracketListString(names));
        }

        // 职位类型：保存为中文名（单值）
        if (config.getJobType() != null) {
            java.util.List<String> list = bossService.parseListString(config.getJobType());
            java.util.List<String> names = bossService.toNames("jobType", list);
            String name = names != null && !names.isEmpty()
                    ? names.get(0)
                    : (bossService.getOptionByTypeAndCode("jobType", config.getJobType()) != null
                        ? bossService.getOptionByTypeAndCode("jobType", config.getJobType()).getName()
                        : config.getJobType());
            config.setJobType(name);
        }

        // 写进 config/boss.yaml（配置的权威来源）。
        // 这一步必须在返回前做——否则下次投递时 syncConfigFromFile() 会用旧文件把这次修改覆盖回去。
        // saveConfigToFile 内部写完文件会立刻回写库，所以下面的库更新只是保持原有的返回值语义。
        bossService.saveConfigToFile(config);

        // 为避免每次新增导致错乱：当ID缺失时也执行“选择性更新第一条”策略
        // 若存在ID，按ID更新；否则更新首条记录（若不存在则插入）
        if (config.getId() != null) {
            return bossService.updateConfig(config);
        }
        return bossService.saveOrUpdateFirstSelective(config);
  }

    /**
     * 将关键词字符串标准化为 JSON 字符串列表。
     * 支持输入形式：
     * 1) 逗号分隔："大模型, Python, Golang"
     * 2) 中文逗号："大模型，Python，Golang"
     * 3) 括号列表："[大模型,Python]" 或 "[\"大模型\",\"Python\"]"
     * 4) JSON 数组："["大模型","Python"]"
     */
    private String normalizeKeywords(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return "[]";

        ObjectMapper mapper = new ObjectMapper();
        // 优先尝试 JSON 解析
        if (s.startsWith("[") && s.endsWith("]")) {
            try {
                JsonNode node = mapper.readTree(s);
                if (node.isArray()) {
                    java.util.List<String> list = new ArrayList<>();
                    node.forEach(it -> list.add(it.asText().trim()));
                    return mapper.writeValueAsString(list);
                }
            } catch (Exception ignore) {
                // 非严格 JSON，继续走分隔解析
            }
            // 去除括号后按逗号拆分
            s = s.substring(1, s.length() - 1);
        }

        java.util.List<String> items = Arrays.stream(s.split("[,，]"))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .map(v -> v.replaceAll("^\"|\"$", ""))
                .collect(Collectors.toList());
        try {
            return mapper.writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * 获取指定类型的选项列表
     */
    @GetMapping("/options/{type}")
    public List<BossOptionEntity> getOptionsByType(@PathVariable String type) {
        return bossService.getOptionsByType(type);
    }

    /**
     * 获取黑名单列表
     */
    @GetMapping("/blacklist")
    public List<BlacklistEntity> getBlacklist() {
        return bossService.getAllBlacklist();
    }

    /**
     * 添加黑名单
     */
    @PostMapping("/blacklist")
    public BlacklistEntity addBlacklist(@RequestBody BlacklistEntity blacklist) {
        // 添加黑名单，将keyword映射到value
        String value = blacklist.getValue() != null ? blacklist.getValue() : "";
        String type = blacklist.getType() != null ? blacklist.getType() : "boss";

        boolean success = bossService.addBlacklist(type, value);
        if (success) {
            // 添加成功，返回新创建的实体
            blacklist.setId(null); // 重新从数据库获取
            return blacklist;
        } else {
            // 已存在或添加失败
            return blacklist;
        }
    }

    /**
     * 删除黑名单
     */
    @DeleteMapping("/blacklist/{id}")
    public boolean deleteBlacklist(@PathVariable Long id) {
        // 直接通过ID查找并删除
        List<BlacklistEntity> allBlacklist = bossService.getAllBlacklist();
        BlacklistEntity entity = allBlacklist.stream()
                .filter(e -> e.getId().equals(id))
                .findFirst()
                .orElse(null);
        if (entity != null) {
            return bossService.removeBlacklist(entity.getType(), entity.getValue());
        }
        return false;
    }
}
