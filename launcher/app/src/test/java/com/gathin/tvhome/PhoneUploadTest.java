package com.gathin.tvhome;

import org.junit.Test;

import java.net.InetAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The phone-photo server listens on the TV's LAN address with no account: what it lets in is the whole of its
 * security (PhoneUpload). Each check here is one way a stranger's request or a bad file could get through.
 */
public class PhoneUploadTest {

    private static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);   // a literal: no DNS lookup
    }

    @Test public void theTokenMustBeThereAndMatchExactly() {
        String token = "Zk3x9Q";
        assertTrue(PhoneUpload.tokenOk("t=Zk3x9Q", token));
        assertTrue(PhoneUpload.tokenOk("a=1&t=Zk3x9Q&b=2", token));
        assertFalse(PhoneUpload.tokenOk("", token));
        assertFalse(PhoneUpload.tokenOk("t=", token));
        assertFalse(PhoneUpload.tokenOk("t=Zk3x9", token));
        assertFalse(PhoneUpload.tokenOk("t=Zk3x9Q1", token));
        assertFalse(PhoneUpload.tokenOk("t=zk3x9q", token));
        assertFalse(PhoneUpload.tokenOk("x=Zk3x9Q", token));
        assertFalse(PhoneUpload.tokenOk(null, token));
        assertFalse("no token set lets nothing in", PhoneUpload.tokenOk("t=", ""));
    }

    @Test public void privateAndLinkLocalAddressesAreLetIn() throws Exception {
        for (String a : new String[]{"10.0.0.236", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.50",
                "169.254.10.20", "127.0.0.1", "fe80::1", "fd12:3456::1"}) {
            assertTrue(a, PhoneUpload.privateAddress(ip(a)));
        }
    }

    @Test public void publicAddressesAreRefused() throws Exception {
        for (String a : new String[]{"8.8.8.8", "172.15.255.255", "172.32.0.1", "192.169.0.1", "11.0.0.1",
                "100.64.0.1", "2001:4860:4860::8888"}) {
            assertFalse(a, PhoneUpload.privateAddress(ip(a)));
        }
        assertFalse(PhoneUpload.privateAddress(null));
    }

    private static byte[] head(int... b) {
        byte[] h = new byte[12];
        for (int i = 0; i < b.length; i++) h[i] = (byte) b[i];
        return h;
    }

    @Test public void photosAreKnownByTheirFirstBytes() {
        assertTrue("JPEG", PhoneUpload.imageHeader(head(0xFF, 0xD8, 0xFF, 0xE0), 12));
        assertTrue("PNG", PhoneUpload.imageHeader(head(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A), 12));
        assertTrue("WebP", PhoneUpload.imageHeader(head('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'), 12));
    }

    @Test public void textAndOtherFilesAreRefused() {
        assertFalse("HTML", PhoneUpload.imageHeader("<html><body>".getBytes(), 12));
        assertFalse("text", PhoneUpload.imageHeader("hello world!".getBytes(), 12));
        assertFalse("WAV is RIFF too", PhoneUpload.imageHeader(head('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E'), 12));
        assertFalse("GIF", PhoneUpload.imageHeader("GIF89a......".getBytes(), 12));
        assertFalse("too short", PhoneUpload.imageHeader(head(0xFF, 0xD8), 2));
        assertFalse(PhoneUpload.imageHeader(null, 12));
    }

    @Test public void sizeAndCountCapsHold() {
        assertEquals(0, PhoneUpload.refusal(1, 0));
        assertEquals(0, PhoneUpload.refusal(PhoneUpload.MAX_BYTES, PhoneUpload.MAX_PHOTOS - 1));
        assertEquals(413, PhoneUpload.refusal(PhoneUpload.MAX_BYTES + 1, 0));
        assertEquals("no Content-Length", 413, PhoneUpload.refusal(-1, 0));
        assertEquals(413, PhoneUpload.refusal(0, 0));
        assertEquals(429, PhoneUpload.refusal(1000, PhoneUpload.MAX_PHOTOS));
    }

    @Test public void thePageEscapesItsTranslatedText() {
        String page = PhoneUpload.page("T<i>", "H & H", "B", "C", "It's {i} of {n}", "{n} sent", "None</script>");
        assertTrue(page.contains("<title>T&lt;i&gt;</title>"));
        assertTrue(page.contains("<h1>H &amp; H</h1>"));
        assertTrue("a quote must not end the script string", page.contains("It\\'s {i} of {n}"));
        assertFalse("text must not close the script", page.contains("None</script>"));
    }
}
