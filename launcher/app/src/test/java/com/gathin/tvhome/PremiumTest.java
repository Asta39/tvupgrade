package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.LocalDate;

public class PremiumTest {
    @Test
    public void weatherCodesMapToEffects() {
        assertEquals(WeatherFx.NONE, WeatherFx.kindOf(0));
        assertEquals(WeatherFx.NONE, WeatherFx.kindOf(3));
        assertEquals(WeatherFx.FOG, WeatherFx.kindOf(45));
        assertEquals(WeatherFx.RAIN, WeatherFx.kindOf(61));
        assertEquals(WeatherFx.RAIN, WeatherFx.kindOf(81));
        assertEquals(WeatherFx.SNOW, WeatherFx.kindOf(73));
        assertEquals(WeatherFx.STORM, WeatherFx.kindOf(95));
    }

    @Test
    public void seasonalAccentFollowsTheMonth() {
        assertEquals(0xFFE5484D, ThemePacks.seasonal(LocalDate.of(2026, 12, 5)));
        assertEquals(0xFF7FB2FF, ThemePacks.seasonal(LocalDate.of(2026, 1, 5)));
        assertEquals(0xFF7ED957, ThemePacks.seasonal(LocalDate.of(2026, 4, 5)));
        assertEquals(0xFFFFB23C, ThemePacks.seasonal(LocalDate.of(2026, 7, 5)));
        assertEquals(0xFFE07A2E, ThemePacks.seasonal(LocalDate.of(2026, 10, 5)));
    }
}
