package com.fnvideo.app;

import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in LAN test: sends no credentials and does not read private media. */
public class NasContractSmokeTest {
    @Test public void deployedNasAcceptsSignatureBeforeRequiringLogin() throws Exception {
        String server = System.getenv("FNVIDEO_TEST_SERVER");
        Assume.assumeTrue(server != null && !server.isEmpty());
        try {
            new FnApi(server, "").libraries();
            fail("Protected library endpoint unexpectedly allowed unauthenticated access");
        } catch (FnApi.FnApiException error) {
            assertEquals("Expected auth failure, not invalid sign or missing route", -2, error.apiCode);
        }
    }
}
