// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer.texture;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.media.MediaFormat;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoSink;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;

@OptIn(markerClass = UnstableApi.class)
@RunWith(RobolectricTestRunner.class)
public final class ResizingVideoRendererTest {
  private ResizingVideoRenderer renderer;
  private VideoSink videoSink;
  private Format initialFormat;

  @Before
  public void setUp() {
    renderer =
        new ResizingVideoRenderer(
            new MediaCodecVideoRenderer.Builder(ApplicationProvider.getApplicationContext()));
    videoSink = mock(VideoSink.class);
    initialFormat = new Format.Builder().setWidth(900).setHeight(1250).setFrameRate(30).build();
  }

  @Test
  public void decodedVideoSizeUsesInclusiveCropBounds() {
    MediaFormat mediaFormat = croppedMediaFormat();

    VideoSize size = ResizingVideoRenderer.getDecodedVideoSize(initialFormat, mediaFormat);

    assertEquals(1080, size.width);
    assertEquals(1500, size.height);
    assertEquals(1.0f, size.pixelWidthHeightRatio, 0.0f);
  }

  @Test
  public void decodedVideoSizeWithoutCropUsesMediaFormatDimensions() {
    MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", 1088, 1504);

    VideoSize size = ResizingVideoRenderer.getDecodedVideoSize(initialFormat, mediaFormat);

    assertEquals(1088, size.width);
    assertEquals(1504, size.height);
  }

  @Test
  public void decodedVideoSizeWithIncompleteCropUsesMediaFormatDimensions() {
    MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", 1088, 1504);
    mediaFormat.setInteger("crop-left", 4);
    mediaFormat.setInteger("crop-right", 1083);
    mediaFormat.setInteger("crop-top", 2);

    VideoSize size = ResizingVideoRenderer.getDecodedVideoSize(initialFormat, mediaFormat);

    assertEquals(1088, size.width);
    assertEquals(1504, size.height);
  }

  @Test
  public void decodedVideoSizeAt90DegreesSwapsDimensionsAndInvertsRatio() {
    assertRotatedVideoSize(90);
  }

  @Test
  public void decodedVideoSizeAt270DegreesSwapsDimensionsAndInvertsRatio() {
    assertRotatedVideoSize(270);
  }

  @Test
  public void outputFormatChangeRegistersNewSinkInputSize() {
    registerInitialStream();
    Format nextFormat = initialFormat.buildUpon().setWidth(1080).setHeight(1500).build();

    renderer.onOutputFormatChanged(nextFormat, croppedMediaFormat());

    ArgumentCaptor<Format> formats = ArgumentCaptor.forClass(Format.class);
    verify(videoSink, times(2))
        .onInputStreamChanged(
            eq(VideoSink.INPUT_TYPE_SURFACE), formats.capture(), anyLong(), anyInt(), anyList());
    assertEquals(initialFormat, formats.getAllValues().get(0));
    assertEquals(nextFormat, formats.getAllValues().get(1));
    verify(videoSink)
        .onInputStreamChanged(
            eq(VideoSink.INPUT_TYPE_SURFACE),
            eq(nextFormat),
            anyLong(),
            eq(VideoSink.RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED),
            anyList());
    verifyNoMoreInteractions(videoSink);
  }

  @Test
  public void repeatedOutputSizeDoesNotRegisterAnotherStream() {
    registerInitialStream();

    renderer.onOutputFormatChanged(initialFormat, croppedMediaFormat());
    renderer.onOutputFormatChanged(initialFormat, croppedMediaFormat());

    verify(videoSink, times(2))
        .onInputStreamChanged(
            eq(VideoSink.INPUT_TYPE_SURFACE),
            any(Format.class),
            anyLong(),
            anyInt(),
            anyList());
    verifyNoMoreInteractions(videoSink);
  }

  @Test
  public void unchangedOutputSizeDoesNotRegisterAnotherStream() {
    registerInitialStream();

    renderer.onOutputFormatChanged(
        initialFormat, MediaFormat.createVideoFormat("video/avc", 900, 1250));

    verify(videoSink)
        .onInputStreamChanged(
            eq(VideoSink.INPUT_TYPE_SURFACE), eq(initialFormat), anyLong(), anyInt(), anyList());
    verifyNoMoreInteractions(videoSink);
  }

  @Test
  public void outputFormatChangeWithoutSinkDoesNotRegisterStream() {
    renderer.onOutputFormatChanged(initialFormat, croppedMediaFormat());

    verifyNoInteractions(videoSink);
  }

  @Test
  public void zeroOutputSizeDoesNotRegisterAnotherStream() {
    registerInitialStream();

    renderer.onOutputFormatChanged(
        initialFormat, MediaFormat.createVideoFormat("video/avc", 0, 1500));

    verify(videoSink)
        .onInputStreamChanged(
            eq(VideoSink.INPUT_TYPE_SURFACE), eq(initialFormat), anyLong(), anyInt(), anyList());
    verifyNoMoreInteractions(videoSink);
  }

  private void registerInitialStream() {
    renderer.changeVideoSinkInputStream(
        videoSink,
        VideoSink.INPUT_TYPE_SURFACE,
        initialFormat,
        VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY);
  }

  private void assertRotatedVideoSize(int rotationDegrees) {
    Format format =
        initialFormat
            .buildUpon()
            .setRotationDegrees(rotationDegrees)
            .setPixelWidthHeightRatio(2.0f)
            .build();

    VideoSize size = ResizingVideoRenderer.getDecodedVideoSize(format, croppedMediaFormat());

    assertEquals(1500, size.width);
    assertEquals(1080, size.height);
    assertEquals(0.5f, size.pixelWidthHeightRatio, 0.0f);
  }

  private static MediaFormat croppedMediaFormat() {
    MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", 1088, 1504);
    mediaFormat.setInteger("crop-left", 4);
    mediaFormat.setInteger("crop-right", 1083);
    mediaFormat.setInteger("crop-top", 2);
    mediaFormat.setInteger("crop-bottom", 1501);
    return mediaFormat;
  }
}
