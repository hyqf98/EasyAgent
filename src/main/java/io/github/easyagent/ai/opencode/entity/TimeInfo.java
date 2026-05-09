package io.github.easyagent.ai.opencode.entity;

import lombok.Builder;

/**
 * 时间范围信息。
 *
 * @param start 开始时间戳
 * @param end   结束时间戳
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Builder
public record TimeInfo(
        long start,
        long end
) {}
