package com.streamarr.server.services.library;

import com.streamarr.server.AbstractWireMockIntegrationTest;
import com.streamarr.server.fakes.FakeFfprobeService;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.streaming.FfprobeService;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.TranscodeExecutor;
import org.springframework.test.context.bean.override.convention.TestBean;

abstract class AbstractScanningIntegrationTest extends AbstractWireMockIntegrationTest {

  @TestBean TranscodeExecutor transcodeExecutor;
  @TestBean FfprobeService ffprobeService;
  @TestBean SegmentStore segmentStore;

  static TranscodeExecutor transcodeExecutor() {
    return new FakeTranscodeExecutor();
  }

  static FfprobeService ffprobeService() {
    return new FakeFfprobeService();
  }

  static SegmentStore segmentStore() {
    return new FakeSegmentStore();
  }
}
