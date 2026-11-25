package com.example.icuapk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class HostNotifyReassemblerTest {
    private static final String ENVIRONMENT_FRAME = "{\"cmd\":\"get_all_status\",\"zone\":\"left\","
            + "\"data\":{\"temp\":276,\"o2\":215,\"humidity\":357,\"co2\":478}}";

    @Test
    public void splitEnvironmentFrameReachesEnvironmentParser() throws Exception {
        HostNotifyReassembler reassembler = new HostNotifyReassembler(1024, 3000L);
        assertTrue(reassembler.append(ENVIRONMENT_FRAME.substring(0, 23), 0L).frames.isEmpty());
        HostNotifyReassembler.Result result = reassembler.append(ENVIRONMENT_FRAME.substring(23), 20L);

        assertEquals(1, result.frames.size());
        assertEnvironmentValues(result.frames.get(0));
        assertEquals(0, reassembler.getBufferLength());
    }

    @Test
    public void brokenOuterAndInnerOpenersCannotBlockLaterEnvironmentFrame() throws Exception {
        HostNotifyReassembler reassembler = new HostNotifyReassembler(1024, 3000L);
        HostNotifyReassembler.Result result = reassembler.append(
                "{\"cmd\":\"get_all_status\",\"data\":{\"temp\":27" + ENVIRONMENT_FRAME, 0L);

        assertEquals(1, result.frames.size());
        assertEquals("broken_half_frame", result.lastDropReason);
        assertEnvironmentValues(result.frames.get(0));
        assertEquals(0, reassembler.getBufferLength());
    }

    @Test
    public void noiseWithoutJsonStartIsDiscardedImmediately() {
        HostNotifyReassembler reassembler = new HostNotifyReassembler(1024, 3000L);
        HostNotifyReassembler.Result result = reassembler.append("not-a-json-frame", 0L);

        assertEquals(0, reassembler.getBufferLength());
        assertEquals(1, result.resetCount);
        assertEquals("no_json_start", result.lastDropReason);
    }

    @Test
    public void prefixNoiseDoesNotGrowWithAnIncompleteFrame() {
        HostNotifyReassembler reassembler = new HostNotifyReassembler(1024, 3000L);
        String partial = "{\"cmd\":\"get_all_status\"";
        HostNotifyReassembler.Result result = reassembler.append("xxxx" + partial, 0L);

        assertEquals(partial.length(), reassembler.getBufferLength());
        assertEquals("prefix_noise", result.lastDropReason);
    }

    @Test
    public void incompleteFrameTimesOutAndNextFreshFrameIsAccepted() throws Exception {
        HostNotifyReassembler reassembler = new HostNotifyReassembler(1024, 100L);
        reassembler.append("{\"cmd\":\"get_all_status\",\"data\":{", 0L);
        HostNotifyReassembler.Result reset = reassembler.append("still-broken", 100L);

        assertEquals(0, reassembler.getBufferLength());
        assertEquals(1, reset.resetCount);
        assertEquals("frame_timeout", reset.lastDropReason);

        HostNotifyReassembler.Result recovered = reassembler.append(ENVIRONMENT_FRAME, 101L);
        assertEquals(1, recovered.frames.size());
        assertEnvironmentValues(recovered.frames.get(0));
    }

    @Test
    public void overflowHardResetAllowsNextFreshEnvironmentFrame() throws Exception {
        HostNotifyReassembler reassembler = new HostNotifyReassembler(128, 3000L);
        StringBuilder garbage = new StringBuilder("{\"cmd\":\"get_all_status\",\"data\":");
        while (garbage.length() <= 129) {
            garbage.append('x');
        }
        HostNotifyReassembler.Result overflow = reassembler.append(garbage.toString(), 0L);

        assertEquals(0, reassembler.getBufferLength());
        assertEquals(1, overflow.resetCount);
        assertEquals("buffer_overflow", overflow.lastDropReason);

        HostNotifyReassembler.Result recovered = reassembler.append(ENVIRONMENT_FRAME, 1L);
        assertEquals(1, recovered.frames.size());
        assertEnvironmentValues(recovered.frames.get(0));
    }

    @Test
    public void oneChunkCanReleaseMultipleFramesAndHonorsEscapedStrings() {
        HostNotifyReassembler reassembler = new HostNotifyReassembler(1024, 3000L);
        String ack = "{\"cmd\":\"set_temp\",\"ret\":\"ok\",\"note\":\"} { \\\" still string\"}";
        HostNotifyReassembler.Result result = reassembler.append(ack + ENVIRONMENT_FRAME, 0L);

        assertEquals(2, result.frames.size());
        assertEquals(ack, result.frames.get(0));
        assertEquals(ENVIRONMENT_FRAME, result.frames.get(1));
        assertEquals(0, reassembler.getBufferLength());
    }

    private static void assertEnvironmentValues(String frame) throws Exception {
        JSONObject data = new JSONObject(frame).getJSONObject("data");
        HostEnvironmentParser.Reading reading = HostEnvironmentParser.read(data);
        assertEquals(27.6f, reading.temperature, 0.001f);
        assertEquals(21.5f, reading.oxygen, 0.001f);
        assertEquals(35.7f, reading.humidity, 0.001f);
        assertEquals(Integer.valueOf(478), reading.co2);
    }
}
