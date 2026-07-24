package io.github.ozozorz.aipartner.work;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.life.ActivityLocation;
import io.github.ozozorz.aipartner.skill.MaidSkillSet;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 向工作规则公开的最小服务端上下文和合法活动边界。
 */
public record MaidWorkContext(
        AiPartnerEntity partner,
        ServerLevel level,
        ActivityLocation boundary,
        MaidSkillSet skills
) {
    public MaidWorkContext {
        Objects.requireNonNull(partner, "partner");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(skills, "skills");
    }

    public boolean isLegal(BlockPos position) {
        return boundary.contains(level, position);
    }
}
