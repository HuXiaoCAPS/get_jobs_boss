package com.getjobs.worker.boss;

import com.getjobs.application.entity.BossJobDataEntity;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.platform.DeliveryStore;
import com.getjobs.worker.platform.model.JobDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * {@link DeliveryStore} 的 Boss 实现：把流程层的数据操作翻译成 {@code boss_data} / 黑名单的读写。
 *
 * <p>它<b>刻意</b>放在 {@code worker.boss} 包里 —— 它是"平台侧"的东西：知道
 * {@code boss_data} 的表结构、也知道 Boss 详情接口 JSON 长什么样。
 * 流程层只认 {@link DeliveryStore} 接口，所以换平台不用动流程。
 *
 * <p>将来接第二个平台时：它会有自己的 {@code XxxDeliveryStore}；数据层要不要合并成
 * 一张带 {@code platform} 列的通表，属于阶段 4/5 的决定，不在本次范围。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BossDeliveryStore implements DeliveryStore {

    private final BossService bossService;

    // ------------------------------------------------------------------
    // 黑名单
    // ------------------------------------------------------------------

    @Override
    public Set<String> blacklist(String type) {
        try {
            return switch (type == null ? "" : type) {
                case BLACKLIST_COMPANY -> orEmpty(bossService.getBlackCompanies());
                case BLACKLIST_RECRUITER -> orEmpty(bossService.getBlackRecruiters());
                case BLACKLIST_JOB -> orEmpty(bossService.getBlackJobs());
                default -> Collections.emptySet();
            };
        } catch (Exception e) {
            log.warn("读取黑名单失败（按空处理）: type={} err={}", type, e.getMessage());
            return Collections.emptySet();
        }
    }

    @Override
    public void addBlacklist(String type, String value) {
        bossService.addBlacklist(type, value);
    }

    // ------------------------------------------------------------------
    // 岗位记录
    // ------------------------------------------------------------------

    @Override
    public Set<String> deliveredExternalIds(String platform) {
        // 现在只有 boss 一张岗位表，platform 参数暂不参与查询
        try {
            return orEmpty(bossService.getDeliveredEncryptIds());
        } catch (Exception e) {
            log.warn("读取已投递岗位失败（按空处理）：{}", e.getMessage());
            return Collections.emptySet();
        }
    }

    @Override
    public Set<String> deliveredCompanies(String platform) {
        // 同一家公司不重复投递：公司名下任何一个岗位投出去了，它其余岗位就都跳过
        try {
            return orEmpty(bossService.getDeliveredCompanies());
        } catch (Exception e) {
            log.warn("读取已投递公司失败（本次不做公司级去重）：{}", e.getMessage());
            return Collections.emptySet();
        }
    }

    @Override
    public void saveDiscovered(String platform, JobDetail detail, String status, String note) {
        String externalId = detail.getExternalId();
        if (externalId == null || externalId.isEmpty()) {
            return; // 没有平台内唯一 id 就没法去重，不入库
        }
        try {
            String recruiterId = detail.getRecruiterId();
            boolean exists = recruiterId != null && !recruiterId.isEmpty()
                    ? bossService.existsBossJob(externalId, recruiterId)
                    : bossService.existsBossJobByEncryptId(externalId);
            if (exists) {
                return;
            }

            BossJobDataEntity entity = new BossJobDataEntity();
            entity.setEncryptId(externalId);
            entity.setEncryptUserId(recruiterId);
            entity.setJobName(detail.getJobName());
            entity.setSalary(detail.getSalary());
            entity.setLocation(detail.getCity());
            entity.setExperience(detail.getExperience());
            entity.setDegree(detail.getDegree());
            entity.setJobDescription(detail.getJdText());
            entity.setCompanyName(detail.getCompanyName());
            entity.setHrName(detail.getHrName());
            entity.setHrPosition(detail.getHrPosition());
            entity.setHrActiveStatus(detail.getHrActiveText());
            entity.setJobUrl(detail.getDetailUrl());
            entity.setFilterNote(note);
            entity.setDeliveryStatus(status);

            // 详情 JSON 里还有一批"流程用不上、但数据页要展示"的字段（行业/规模/融资/公司介绍/地址…）。
            // 这是平台侧的解析知识，所以补在这里，而不是污染平台无关的 JobDetail 模型。
            fillFromRawJson(entity, detail.getRaw());

            bossService.insertBossJob(entity);
            log.debug("岗位入库：{} | 公司：{} | 状态：{}",
                    entity.getJobName(), entity.getCompanyName(), entity.getDeliveryStatus());
        } catch (Exception e) {
            log.warn("岗位入库失败：{}", e.getMessage());
        }
    }

    /** 从 Boss 详情接口的原始 JSON 里补齐展示字段（结构变了也只影响这里） */
    private void fillFromRawJson(BossJobDataEntity entity, String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(raw);
            org.json.JSONObject zpData = root.optJSONObject("zpData");
            if (zpData == null) {
                return;
            }
            org.json.JSONObject jobInfo = zpData.optJSONObject("jobInfo");
            if (jobInfo != null) {
                String desc = jobInfo.optString("postDescription", null);
                if (desc != null && !desc.isEmpty()) {
                    entity.setJobDescription(desc);
                }
                entity.setRecruitmentStatus(jobInfo.optString("jobStatusDesc", null));
                entity.setCompanyAddress(jobInfo.optString("address", null));
            }
            org.json.JSONObject brand = zpData.optJSONObject("brandComInfo");
            if (brand != null) {
                entity.setIndustry(brand.optString("industryName", null));
                entity.setIntroduce(brand.optString("introduce", null));
                entity.setFinancingStage(brand.optString("stageName", null));
                entity.setCompanyScale(brand.optString("scaleName", null));
            }
        } catch (Exception e) {
            log.debug("从原始 JSON 补充岗位字段失败：{}", e.getMessage());
        }
    }

    @Override
    public void updateStatus(String platform, String externalId, String recruiterId, String status) {
        if (externalId == null || externalId.isEmpty() || recruiterId == null || recruiterId.isEmpty()) {
            log.debug("缺少 encryptId / encryptUserId，无法更新投递状态：{}", externalId);
            return;
        }
        bossService.updateDeliveryStatus(externalId, recruiterId, status);
    }

    // ------------------------------------------------------------------
    // 聊天快照
    // ------------------------------------------------------------------

    @Override
    public Map<String, String> chatSnapshot(String platform) {
        try {
            Map<String, String> snapshot = bossService.getChatSnapshot();
            return snapshot == null ? Collections.emptyMap() : snapshot;
        } catch (Exception e) {
            log.warn("读取聊天快照失败（按空处理）：{}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Override
    public void replaceChatSnapshot(String platform, Map<String, String> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        bossService.replaceChatSnapshot(snapshot);
    }

    private static Set<String> orEmpty(Set<String> in) {
        return in == null ? Collections.emptySet() : in;
    }
}
