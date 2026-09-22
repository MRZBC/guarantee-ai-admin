package com.guarantee.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 提案过期清理与容量巡检定时任务（T-11 / SYS-C-09 / SYS-A-18）。
 *
 * <p><b>为什么必须有这个任务</b>：未确认的提案如果不清理，会永久停留在 {@code PENDING}，
 * 用户会以为"已经生效了"（RK-05）；同时敏感参数的加密暂存也需要随之清理，
 * 否则 D-4 的"尽量缩短敏感数据存在窗口"就落不了地。</p>
 *
 * <p><b>容量巡检</b>：把"在线行数 / 最老在线记录时间"打进日志并在超出保留窗口时告警。
 * 没有这个巡检，滚动归档失败会静默退化为"永久在线"，而系统管理域数据量小、
 * 症状可能数年后才显形（RK-14）。</p>
 */
@Component
public class ProposalMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(ProposalMaintenanceJob.class);

    /** 在线可查窗口（SYS-A-13：24 个月）。 */
    private static final int ONLINE_MONTHS = 24;

    /** 在线行数告警阈值（SYS-A-15：100 万行）。 */
    private static final long ROW_ALERT_THRESHOLD = 1_000_000L;

    private final ProposalService proposalService;
    private final OperationAuditService auditService;

    public ProposalMaintenanceJob(ProposalService proposalService, OperationAuditService auditService) {
        this.proposalService = proposalService;
        this.auditService = auditService;
    }

    /** 每分钟清理过期提案与过期密文（SYS-C-09 / T-11）。 */
    @Scheduled(fixedDelayString = "${guarantee.ai.proposal-expire-interval-ms:60000}")
    public void expireProposals() {
        try {
            int expired = proposalService.expireOverdue();
            int purged = proposalService.purgeExpiredSecrets();
            if (expired > 0 || purged > 0) {
                log.info("提案维护完成：过期 {} 条，清理密文 {} 条", expired, purged);
            }
        } catch (RuntimeException ex) {
            // 定时任务失败不得影响主流程，但必须留下明确日志
            log.error("提案过期清理失败", ex);
        }
    }

    /**
     * 每小时做一次容量巡检（SYS-A-18）。
     *
     * <p>巡检项：在线行数、最老在线记录时间、在线窗口是否超出保留策略。</p>
     */
    @Scheduled(fixedDelayString = "${guarantee.ai.audit-inspect-interval-ms:3600000}")
    public void inspectAuditCapacity() {
        try {
            long rows = auditService.onlineRows();
            LocalDateTime oldest = auditService.oldestOperatedAt();
            if (oldest == null) {
                log.info("操作审计容量巡检：在线 0 行");
                return;
            }
            long onlineDays = Duration.between(oldest, LocalDateTime.now()).toDays();
            log.info("操作审计容量巡检：在线 {} 行，最老在线记录 {}（约 {} 天）", rows, oldest, onlineDays);

            if (onlineDays > ONLINE_MONTHS * 31L) {
                log.error("容量告警：操作审计在线窗口已超过 {} 个月（最老记录 {}），"
                        + "滚动归档可能未生效，请检查 ai_operation_audit 的分区与归档任务（SYS-A-18）",
                        ONLINE_MONTHS, oldest);
            }
            if (rows > ROW_ALERT_THRESHOLD) {
                log.error("容量告警：操作审计在线行数 {} 已超过阈值 {}，"
                        + "请按 SYS-A-15 评估把在线窗口从 24 个月收缩到 12 个月",
                        rows, ROW_ALERT_THRESHOLD);
            }
        } catch (RuntimeException ex) {
            log.error("操作审计容量巡检失败", ex);
        }
    }
}
