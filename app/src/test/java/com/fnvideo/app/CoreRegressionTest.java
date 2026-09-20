package com.fnvideo.app;

import org.junit.Test;

public class CoreRegressionTest {
    @Test public void serverAddressesAndTokensAreOriginScoped() { CoreRegressionChecks.addresses(); }
    @Test public void onlyLoadedCurrentMediaOwnsResumePositions() { CoreRegressionChecks.playbackOwnership(); }
    @Test public void filteredPagesAdvanceWithBoundedAndAcyclicRequests() { CoreRegressionChecks.pagination(); }
    @Test public void authenticationUsesTypedFailuresNotMessages() { CoreRegressionChecks.authentication(); }
}
