package io.github.ozozorz.aipartner.core.behavior;

import io.github.ozozorz.aipartner.entity.AiPartnerEntity;
import io.github.ozozorz.aipartner.entity.PartnerMode;
import java.util.Objects;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 聚合手动指令、有限任务显示状态和 GUI 暂停状态，并向客户端同步唯一有效模式。
 */
public final class MaidBehaviorController {
    private static final String MANUAL_DIRECTIVE_TAG = "ManualDirective";

    private final AiPartnerEntity partner;
    private ManualDirective manualDirective = ManualDirective.NONE;
    private PartnerMode taskMode = PartnerMode.IDLE;
    private PartnerMode backgroundMode = PartnerMode.IDLE;
    private PartnerMode temporaryInterruptionMode = PartnerMode.IDLE;
    private boolean inventoryMenuOpen;

    public MaidBehaviorController(AiPartnerEntity partner) {
        this.partner = Objects.requireNonNull(partner, "partner");
    }

    /**
     * 激活一个长期指令并清除有限任务的显示状态。
     */
    public void activateDirective(ManualDirective directive) {
        manualDirective = Objects.requireNonNull(directive, "directive");
        taskMode = PartnerMode.IDLE;
        synchronizeMode();
    }

    /**
     * 有限任务开始或切换阶段时更新对外显示模式。
     */
    public void activateTaskMode(PartnerMode mode) {
        manualDirective = ManualDirective.NONE;
        taskMode = Objects.requireNonNull(mode, "mode");
        synchronizeMode();
    }

    /**
     * 清除手动指令和任务状态，返回空闲。
     */
    public void clearActivity() {
        manualDirective = ManualDirective.NONE;
        taskMode = PartnerMode.IDLE;
        synchronizeMode();
    }

    /**
     * 更新日程或普通空闲行为的显示投影，不覆盖手动指令和有限任务。
     */
    public void setBackgroundMode(PartnerMode mode) {
        backgroundMode = Objects.requireNonNull(mode, "mode");
        synchronizeMode();
    }

    public ManualDirective manualDirective() {
        return manualDirective;
    }

    public PartnerMode effectiveMode() {
        if (temporaryInterruptionMode != PartnerMode.IDLE) {
            return temporaryInterruptionMode;
        }
        return baseEffectiveMode();
    }

    private PartnerMode baseEffectiveMode() {
        if (manualDirective != ManualDirective.NONE) {
            return manualDirective.displayedMode();
        }
        return taskMode == PartnerMode.IDLE ? backgroundMode : taskMode;
    }

    /**
     * 战斗等瞬时高优先级行为只覆盖显示和 tick 推进，不销毁原任务或长期指令。
     */
    public void setTemporaryInterruption(PartnerMode mode) {
        if (mode == PartnerMode.IDLE) {
            throw new IllegalArgumentException("Temporary interruption mode may not be IDLE");
        }
        temporaryInterruptionMode = Objects.requireNonNull(mode, "mode");
        synchronizeMode();
    }

    public void clearTemporaryInterruption() {
        if (temporaryInterruptionMode != PartnerMode.IDLE) {
            temporaryInterruptionMode = PartnerMode.IDLE;
            synchronizeMode();
        }
    }

    public boolean isTemporarilyInterrupted() {
        return temporaryInterruptionMode != PartnerMode.IDLE;
    }

    public boolean isFollowing() {
        return manualDirective == ManualDirective.FOLLOW;
    }

    public boolean isInventoryMenuOpen() {
        return inventoryMenuOpen;
    }

    /**
     * GUI 打开时只施加临时暂停，不覆盖当前任务或长期指令。
     */
    public void setInventoryMenuOpen(boolean open) {
        inventoryMenuOpen = open;
        if (open) {
            partner.getNavigation().stop();
        }
    }

    /**
     * 保存新的正交指令字段，同时继续写旧显示模式以兼容 v0.4 工具。
     */
    public void save(ValueOutput output) {
        output.putString(MANUAL_DIRECTIVE_TAG, manualDirective.name());
        output.putString("PartnerMode", baseEffectiveMode().name());
    }

    /**
     * 优先读取 v0.5 指令；旧存档则从 PartnerMode 推导 FOLLOW/STAY。
     */
    public void load(ValueInput input) {
        PartnerMode legacyMode = PartnerMode.fromName(input.getStringOr("PartnerMode", PartnerMode.IDLE.name()));
        manualDirective = input.getString(MANUAL_DIRECTIVE_TAG)
                .map(ManualDirective::fromName)
                .orElseGet(() -> ManualDirective.fromLegacyMode(legacyMode));
        taskMode = manualDirective == ManualDirective.NONE ? legacyMode : PartnerMode.IDLE;
        backgroundMode = PartnerMode.IDLE;
        temporaryInterruptionMode = PartnerMode.IDLE;
        inventoryMenuOpen = false;
        synchronizeMode();
    }

    private void synchronizeMode() {
        partner.syncBehaviorMode(effectiveMode());
    }
}
