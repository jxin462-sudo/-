package com.example.icuapk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class HostModeSelectionPolicyTest {
    @Test
    public void legacyMultipleEnabledStatesResolveToOnlyOneVisibleMode() {
        boolean[] enabled = {true, true, true, false};

        assertEquals(2, HostModeSelectionPolicy.visibleEnabledIndex(enabled, 2, 1));
        assertEquals(1, HostModeSelectionPolicy.visibleEnabledIndex(enabled, -1, 1));
        assertEquals(0, HostModeSelectionPolicy.visibleEnabledIndex(enabled, -1, -1));
        assertEquals(-1, HostModeSelectionPolicy.visibleEnabledIndex(
                new boolean[]{false, false, false, false}, -1, -1));
    }

    @Test
    public void onlyCompleteActivationRetainsSelection() {
        assertEquals(2, HostModeSelectionPolicy.completedEnabledIndex(2, true, true));
        assertEquals(-1, HostModeSelectionPolicy.completedEnabledIndex(2, true, false));
        assertEquals(-1, HostModeSelectionPolicy.completedEnabledIndex(2, false, true));
        assertEquals(-1, HostModeSelectionPolicy.completedEnabledIndex(-1, true, true));
    }

    @Test
    public void switchingModesClosesOnlyOldUniqueSwitchesAndKeepsNewValues() throws Exception {
        JSONObject selected = new JSONObject();
        selected.put("temp", 27.5);
        selected.put("coldLight", true);
        selected.put("redTherapy", false);
        JSONObject oldCardio = new JSONObject();
        oldCardio.put("coldLight", false);
        oldCardio.put("blueTherapy", true);
        oldCardio.put("warmLight", true);
        JSONObject oldCustom = new JSONObject();
        oldCustom.put("blueTherapy", false);
        oldCustom.put("uv", true);

        JSONObject merged = HostModeSelectionPolicy.mergeActivationConfig(selected,
                new JSONObject[]{oldCardio, null, oldCustom},
                new String[]{"coldLight", "redTherapy", "blueTherapy", "warmLight", "uv"});

        assertEquals(27.5, merged.getDouble("temp"), 0.001);
        assertTrue(merged.getBoolean("coldLight"));
        assertFalse(merged.getBoolean("redTherapy"));
        assertFalse(merged.getBoolean("blueTherapy"));
        assertFalse(merged.getBoolean("warmLight"));
        assertFalse(merged.getBoolean("uv"));
        assertFalse(selected.has("blueTherapy"));
        assertTrue(oldCardio.getBoolean("blueTherapy"));
    }

    @Test
    public void emptyNewModeStillClosesFunctionsUniqueToOldModes() throws Exception {
        JSONObject old = new JSONObject();
        old.put("coldLight", true);
        JSONObject secondLegacyMode = new JSONObject();
        secondLegacyMode.put("blueTherapy", true);

        JSONObject merged = HostModeSelectionPolicy.mergeActivationConfig(new JSONObject(),
                new JSONObject[]{old, secondLegacyMode},
                new String[]{"coldLight", "blueTherapy"});

        assertFalse(merged.getBoolean("coldLight"));
        assertFalse(merged.getBoolean("blueTherapy"));
    }
}
