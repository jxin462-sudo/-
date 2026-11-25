package com.example.icuapk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class HostNotifyFrameScannerTest {
    private static final String ENVIRONMENT_FRAME = "{\"cmd\":\"get_all_status\",\"zone\":\"left\","
            + "\"data\":{\"temp\":276,\"o2\":215,\"humidity\":357,\"co2\":478}}";

    @Test
    public void skipsEveryBrokenOpenerBeforeACompleteEnvironmentFrame() throws Exception {
        // 第一条总表丢了尾部：外层与 data 内层各留下一个没有闭合的 '{'。
        String broken = "{\"cmd\":\"get_all_status\",\"data\":{\"temp\":27";
        StringBuilder buffer = new StringBuilder(broken).append(ENVIRONMENT_FRAME);

        int recovery = HostNotifyFrameScanner.findNextCompleteFrame(buffer, 0);

        // 旧逻辑只检查 broken 中 data 的 '{'，因此永远到不了后面的完整总表。
        assertEquals(broken.length(), recovery);
        buffer.delete(0, recovery);
        int end = HostNotifyFrameScanner.findFrameEnd(buffer, 0);
        JSONObject frame = new JSONObject(buffer.substring(0, end + 1));
        HostEnvironmentParser.Reading reading = HostEnvironmentParser.read(frame.getJSONObject("data"));
        assertEquals(27.6f, reading.temperature, 0.001f);
        assertEquals(21.5f, reading.oxygen, 0.001f);
        assertEquals(35.7f, reading.humidity, 0.001f);
        assertEquals(Integer.valueOf(478), reading.co2);
    }

    @Test
    public void keepsOriginalRecoveryWhenTheNextOpenerIsAlreadyComplete() {
        String prefix = "{\"cmd\":\"get_all_status\"";
        StringBuilder buffer = new StringBuilder(prefix).append(ENVIRONMENT_FRAME);

        assertEquals(prefix.length(), HostNotifyFrameScanner.findNextCompleteFrame(buffer, 0));
    }

    @Test
    public void skipsBalancedButInvalidJsonBeforeACompleteFrame() {
        String prefix = "{\"broken\":{not-json}";
        StringBuilder buffer = new StringBuilder(prefix).append(ENVIRONMENT_FRAME);

        assertEquals(prefix.length(), HostNotifyFrameScanner.findNextCompleteFrame(buffer, 0));
    }

    @Test
    public void waitsWhenNoCompleteFrameExistsAndHonorsJsonStrings() {
        StringBuilder waiting = new StringBuilder("{\"cmd\":\"get_all_status\",\"data\":{\"temp\":27");
        assertEquals(-1, HostNotifyFrameScanner.findNextCompleteFrame(waiting, 0));

        String frame = "{\"cmd\":\"get_all_status\",\"note\":\"} { \\\" still string\","
                + "\"data\":{\"temp\":276}}";
        StringBuilder buffer = new StringBuilder(frame);
        int end = HostNotifyFrameScanner.findFrameEnd(buffer, 0);
        assertEquals(buffer.length() - 1, end);
        assertTrue(end > 0);
    }
}
