package com.example.icuapk;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class CaseNumberingTest {
    @Test
    public void emptyZoneStartsAtOne() {
        assertEquals("000001", IcuDashboardView.nextCaseNumberFrom(Collections.<String>emptyList()));
        assertEquals("000001", IcuDashboardView.nextCaseNumberFrom(null));
    }

    @Test
    public void deletingSoleOneAllowsOneAgain() {
        List<String> remaining = new ArrayList<>(Collections.singletonList("000001"));
        assertEquals("000002", IcuDashboardView.nextCaseNumberFrom(remaining));
        remaining.clear();
        assertEquals("000001", IcuDashboardView.nextCaseNumberFrom(remaining));
    }

    @Test
    public void deletingMiddleDoesNotFillGap() {
        assertEquals("000004", IcuDashboardView.nextCaseNumberFrom(
                Arrays.asList("000001", "000003")));
    }

    @Test
    public void deletingHighestAllowsHighestAgain() {
        assertEquals("000003", IcuDashboardView.nextCaseNumberFrom(
                Arrays.asList("000001", "000002")));
    }

    @Test
    public void invalidAndNonpositiveNumbersAreIgnored() {
        assertEquals("000010", IcuDashboardView.nextCaseNumberFrom(
                Arrays.asList(null, "", "abc", "-7", "0", "12.5", " 000009 ")));
        assertEquals("000001", IcuDashboardView.nextCaseNumberFrom(
                Arrays.asList(null, "abc", "-7", "0")));
    }

    @Test
    public void formattingKeepsAtLeastSixDigits() {
        assertEquals("000123", IcuDashboardView.nextCaseNumberFrom(
                Collections.singletonList("122")));
        assertEquals("1000000", IcuDashboardView.nextCaseNumberFrom(
                Collections.singletonList("999999")));
    }

    @Test
    public void calculationDoesNotModifyExistingCasesOrKeepHiddenCounter() {
        List<String> remaining = new ArrayList<>(Arrays.asList("000001", "000003"));
        assertEquals("000004", IcuDashboardView.nextCaseNumberFrom(remaining));
        assertEquals("000004", IcuDashboardView.nextCaseNumberFrom(remaining));
        assertEquals(Arrays.asList("000001", "000003"), remaining);
    }
}
