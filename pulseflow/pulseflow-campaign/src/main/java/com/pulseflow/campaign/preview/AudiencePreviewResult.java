package com.pulseflow.campaign.preview;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人群估算快照，包含人数、计算时间、数据版本和警告。
 *
 * Result of {@link AudiencePreviewService#preview}.
 *
 * <p>{@code calculationMode} tells the frontend whether the count is a real
 * count (SNAPSHOT) or an estimate (ESTIMATE). {@code dataVersion} identifies
 * the profile snapshot used.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudiencePreviewResult {

    private long estimatedCount;
    private LocalDateTime calculatedAt;
    private String dataVersion;
    /** SNAPSHOT (real count via SQL) or ESTIMATE (sampled). v1 always SNAPSHOT. */
    private String calculationMode;
    private List<String> warnings;
}
