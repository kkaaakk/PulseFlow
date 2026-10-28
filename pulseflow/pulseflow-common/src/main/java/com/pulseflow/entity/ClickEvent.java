package com.pulseflow.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 用户点击触达内容的事实记录。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("click_event")
public class ClickEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long taskId;

    private LocalDateTime clickTime;

    private String clickSource;

    private String properties;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
