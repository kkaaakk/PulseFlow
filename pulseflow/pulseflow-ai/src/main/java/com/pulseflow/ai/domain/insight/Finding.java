package com.pulseflow.ai.domain.insight;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Finding {
    private String title;
    private String description;
    private List<String> evidenceKeys;
    /** HIGH / MEDIUM / LOW */
    private String importance;
}
