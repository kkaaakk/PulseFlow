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

/** 带计算时间的用户标签结果，保留命中和未命中历史。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_tag")
public class UserTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String tagName;

    private String tagValue;

    private LocalDateTime calculatedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
