package io.github.ozozorz.aipartner.life;

import io.github.ozozorz.aipartner.core.schedule.ScheduleActivity;
import io.github.ozozorz.aipartner.core.schedule.ScheduleType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 持久化女仆的日程、回家约束和三个相互独立的活动地点。
 */
public final class MaidLifeProfile {
    private static final String DEFAULT_HOME_PREFIX = "DefaultHome";
    private static final String LOCATION_PREFIX = "ActivityLocation";

    private final Map<ActivityLocationType, ActivityLocation> locations = new EnumMap<>(ActivityLocationType.class);
    private ScheduleType scheduleType = ScheduleType.DAY_SHIFT;
    private ActivityLocation defaultHome;
    private boolean homeBound = true;
    private int activityRadius;

    public MaidLifeProfile(int activityRadius) {
        this.activityRadius = validateRadius(activityRadius);
    }

    public ScheduleType scheduleType() {
        return scheduleType;
    }

    public void setScheduleType(ScheduleType scheduleType) {
        this.scheduleType = java.util.Objects.requireNonNull(scheduleType, "scheduleType");
    }

    public boolean homeBound() {
        return homeBound;
    }

    public void setHomeBound(boolean homeBound) {
        this.homeBound = homeBound;
    }

    public int activityRadius() {
        return activityRadius;
    }

    /**
     * 修改全局活动半径，并同步更新已经设置的地点。
     */
    public void setActivityRadius(int radius) {
        activityRadius = validateRadius(radius);
        if (defaultHome != null) {
            defaultHome = new ActivityLocation(defaultHome.dimensionId(), defaultHome.position(), activityRadius);
        }
        locations.replaceAll((type, location) -> new ActivityLocation(
                location.dimensionId(),
                location.position(),
                activityRadius
        ));
    }

    public Optional<ActivityLocation> defaultHome() {
        return Optional.ofNullable(defaultHome);
    }

    public void setDefaultHome(ActivityLocation location) {
        defaultHome = java.util.Objects.requireNonNull(location, "location");
    }

    public Optional<ActivityLocation> location(ActivityLocationType type) {
        return Optional.ofNullable(locations.get(type));
    }

    public Optional<ActivityLocation> locationFor(ScheduleActivity activity) {
        ActivityLocation configured = locations.get(ActivityLocationType.fromActivity(activity));
        return Optional.ofNullable(configured != null ? configured : defaultHome);
    }

    public void setLocation(ActivityLocationType type, ActivityLocation location) {
        locations.put(type, java.util.Objects.requireNonNull(location, "location"));
    }

    public void clearLocation(ActivityLocationType type) {
        locations.remove(type);
    }

    public int configuredLocationMask() {
        int mask = 0;
        for (ActivityLocationType type : locations.keySet()) {
            mask |= 1 << type.ordinal();
        }
        return mask;
    }

    public void save(ValueOutput output) {
        output.putString("ScheduleType", scheduleType.name());
        output.putBoolean("HomeBound", homeBound);
        output.putInt("ActivityRadius", activityRadius);
        if (defaultHome != null) {
            defaultHome.save(output, DEFAULT_HOME_PREFIX);
        }
        for (Map.Entry<ActivityLocationType, ActivityLocation> entry : locations.entrySet()) {
            entry.getValue().save(output, LOCATION_PREFIX + entry.getKey().name());
        }
    }

    public void load(ValueInput input) {
        scheduleType = ScheduleType.fromName(input.getStringOr("ScheduleType", ScheduleType.DAY_SHIFT.name()));
        homeBound = input.getBooleanOr("HomeBound", true);
        int savedRadius = input.getIntOr("ActivityRadius", activityRadius);
        activityRadius = savedRadius >= 1 && savedRadius <= 64 ? savedRadius : activityRadius;
        defaultHome = ActivityLocation.load(input, DEFAULT_HOME_PREFIX).orElse(defaultHome);
        locations.clear();
        for (ActivityLocationType type : ActivityLocationType.values()) {
            ActivityLocation.load(input, LOCATION_PREFIX + type.name())
                    .ifPresent(location -> locations.put(type, location));
        }
    }

    private static int validateRadius(int radius) {
        if (radius < 1 || radius > 64) {
            throw new IllegalArgumentException("Activity radius must be between 1 and 64");
        }
        return radius;
    }
}
