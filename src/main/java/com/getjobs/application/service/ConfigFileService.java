package com.getjobs.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

/**
 * Boss 配置的文件化存储：读写项目根目录下的 {@code config/boss.yaml}。
 *
 * <p>这个类只负责 YAML 的 I/O，不做任何业务转换（name↔code、列表解析等都在
 * {@link BossService} 里做），避免和它形成循环依赖。
 *
 * <p>为什么不用数据库存配置：配置属于"代码"而不是"数据"——放文件才能 git diff、
 * 手工批量编辑、给不同求职方向建分支。投递记录、HR 回复这类真正的数据仍然放库里。
 */
@Slf4j
@Service
public class ConfigFileService {

    /** 配置文件：项目根目录下的 config/boss.yaml */
    private static final Path CONFIG_PATH =
            Paths.get(System.getProperty("user.dir"), "config", "boss.yaml");

    /** YAML 写出风格：多行文本用 | 字面块（好读好改），并且去掉 "---" 文档头 */
    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
                    .disable(com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .build());

    /** 每次写文件时带的说明头 */
    private static final String HEADER =
            "# ============================================================\n" +
            "# Boss 投递配置\n" +
            "# ------------------------------------------------------------\n" +
            "# 生效时机：每次点「开始投递」时重新读取 —— 改完重跑任务即生效，不用重启程序。\n" +
            "# 维护方式：直接编辑本文件，或在网页端配置页保存（两者等价）。\n" +
            "# 本文件含 API KEY，已在 .gitignore 中；结构模板见同目录的 boss.yaml.example。\n" +
            "# ============================================================\n\n";

    /** 配置文件是否存在 */
    public boolean exists() {
        return Files.isRegularFile(CONFIG_PATH);
    }

    /** 配置文件路径（用于日志/提示） */
    public Path path() {
        return CONFIG_PATH;
    }

    /**
     * 读出整份配置（分层结构，对应 YAML 的 search / delivery / ai / notify 四个节点）。
     * 文件不存在或解析失败时返回空 Map —— 调用方据此回退到数据库。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> read() {
        try {
            if (!exists()) {
                return Collections.emptyMap();
            }
            Map<String, Object> data =
                    YAML.readValue(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8), Map.class);
            return data == null ? Collections.emptyMap() : data;
        } catch (Exception e) {
            log.warn("读取 {} 失败，本次改用数据库里的配置：{}", CONFIG_PATH, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 覆盖写入整份配置。失败时返回 false，调用方可以据此给出提示。
     */
    public boolean write(Map<String, Object> data) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            String body = YAML.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.writeString(CONFIG_PATH, HEADER + body, StandardCharsets.UTF_8);
            log.info("已写入配置文件 {}", CONFIG_PATH);
            return true;
        } catch (Exception e) {
            log.warn("写入 {} 失败：{}", CONFIG_PATH, e.getMessage());
            return false;
        }
    }
}
