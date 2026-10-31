package com.guarantee.analysis.service;

import com.guarantee.analysis.vo.OrderReportVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** 只绑定报告业务内容；生成时间不参与，因此相同查询结果可重复核对。 */
public final class ReportFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ReportFingerprint() { }

    public static String of(OrderReportVO report) {
        String content = "analysis-report-v1\n" + JSON.writeValueAsString(List.of(report.comparison(),
                report.trend(), report.regions(), report.insurances(), report.institutions()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
