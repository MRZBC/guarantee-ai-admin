package com.guarantee.ai.controller;

import com.guarantee.ai.analysis.AnalysisSummaryService;
import com.guarantee.common.api.Result;
import com.guarantee.common.exception.BizException;
import com.guarantee.common.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/analysis-summary")
public class AnalysisSummaryController {
    private final AnalysisSummaryService summaries;
    public AnalysisSummaryController(AnalysisSummaryService summaries) { this.summaries = summaries; }

    @PostMapping
    @PreAuthorize("hasAuthority('analysis:overview:view') and hasAuthority('ai:chat')")
    public ResponseEntity<Result<AnalysisSummaryService.Summary>> generate(@RequestBody AnalysisSummaryService.Request request) {
        Long userId = CurrentUser.userId();
        if (userId == null) throw BizException.badRequest("请先登录");
        var result = summaries.generate(userId, request);
        if ("REPORT_CHANGED".equals(result.status()))
            return ResponseEntity.status(409).body(Result.ok(result));
        return ResponseEntity.ok(Result.ok(result));
    }
}
