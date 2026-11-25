package com.example.icuapk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HostTransportPolicyTest {
    @Test
    public void initialNullEnvironmentDoesNotTriggerFourExtraQueries() {
        assertFalse(HostTransportPolicy.shouldRequestEnvironmentFallback(
                false, false, false, false));
    }

    @Test
    public void answeredTotalTableWithMissingEnvironmentCanUseFallback() {
        assertTrue(HostTransportPolicy.shouldRequestEnvironmentFallback(
                false, false, true, false));
    }

    @Test
    public void unansweredTotalTableTimeoutCanUseFallback() {
        assertTrue(HostTransportPolicy.shouldRequestEnvironmentFallback(
                false, false, false, true));
    }

    @Test
    public void unsupportedOrExplicitFallbackStillWorks() {
        assertTrue(HostTransportPolicy.shouldRequestEnvironmentFallback(
                false, true, false, false));
        assertTrue(HostTransportPolicy.shouldRequestEnvironmentFallback(
                true, false, false, false));
    }

    @Test
    public void writeWithResponseIsPreferredWheneverSupported() {
        assertTrue(HostTransportPolicy.preferWriteWithResponse(true, true));
        assertTrue(HostTransportPolicy.preferWriteWithResponse(true, false));
        assertFalse(HostTransportPolicy.preferWriteWithResponse(false, true));
        assertTrue(HostTransportPolicy.preferWriteWithResponse(false, false));
    }

    @Test
    public void writeStartRetriesAreBounded() {
        assertFalse(HostTransportPolicy.shouldRetryWriteStart(0));
        assertFalse(HostTransportPolicy.shouldRetryWriteStart(-1));
        for (int failure = 1; failure < HostTransportPolicy.MAX_WRITE_START_FAILURES; failure++) {
            assertTrue(HostTransportPolicy.shouldRetryWriteStart(failure));
        }
        assertFalse(HostTransportPolicy.shouldRetryWriteStart(
                HostTransportPolicy.MAX_WRITE_START_FAILURES));
        assertFalse(HostTransportPolicy.shouldRetryWriteStart(
                HostTransportPolicy.MAX_WRITE_START_FAILURES + 1));
    }
}
