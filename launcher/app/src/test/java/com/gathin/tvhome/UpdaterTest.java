package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdaterTest {
    @Test
    public void newerComparesNumbersNotText() {
        assertTrue(Updater.newer("1.10.0", "1.9.3"));
        assertTrue(Updater.newer("v1.3", "1.2.9"));
        assertTrue(Updater.newer("2.0.0", "1.2.0"));
        assertFalse(Updater.newer("1.2.0", "1.2.0"));
        assertFalse(Updater.newer("1.2", "1.2.0"));
        assertFalse(Updater.newer("1.1.9", "1.2.0"));
        assertFalse(Updater.newer("", "1.2.0"));
        assertFalse(Updater.newer("1.2.0-beta", "1.2.0"));
    }
}
