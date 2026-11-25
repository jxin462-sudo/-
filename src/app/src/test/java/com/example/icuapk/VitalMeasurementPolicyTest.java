package com.example.icuapk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VitalMeasurementPolicyTest {

    @Test
    public void obviousInvalidFramesNeverBecomeMeasurements() {
        assertFalse(VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_HEART_RATE, 0));
        assertFalse(VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_HEART_RATE, 613));
        assertFalse(VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_SPO2, 101));
        assertFalse(VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_TEMP, 0));
        assertFalse(VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_TEMP, 26));
        assertFalse(VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_RESP, Double.NaN));
        assertFalse(VitalMeasurementPolicy.isValid(7, 80));
        assertFalse(VitalMeasurementPolicy.isValidSample(0L, 0, 80));
    }

    @Test
    public void realSevenParameterValuesRemainUnchangedAndAccepted() {
        double[] values = {78, 121, 82, 76, 98, 37.6, 18};
        for (int param = 0; param < values.length; param++) {
            assertTrue("param " + param,
                    VitalMeasurementPolicy.isValidSample(1700000000000L, param, values[param]));
        }
        assertTrue(VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_HEART_RATE, 400));
        assertTrue(VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_SPO2, 100));
        assertTrue(VitalMeasurementPolicy.isValid(Am4100Manager.PARAM_TEMP, 45));
    }
}
