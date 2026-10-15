// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer.texture;

import android.media.MediaFormat;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoSink;

@OptIn(markerClass = UnstableApi.class)
final class ResizingVideoRenderer extends MediaCodecVideoRenderer {
  @Nullable private VideoSink registeredVideoSink;
  private int registeredWidth;
  private int registeredHeight;

  ResizingVideoRenderer(MediaCodecVideoRenderer.Builder builder) {
    super(builder);
  }

  @Override
  protected void changeVideoSinkInputStream(
      VideoSink videoSink,
      @VideoSink.InputType int inputType,
      Format format,
      @VideoSink.FirstFrameReleaseInstruction int firstFrameReleaseInstruction) {
    registeredVideoSink = videoSink;
    registeredWidth = format.width;
    registeredHeight = format.height;
    super.changeVideoSinkInputStream(videoSink, inputType, format, firstFrameReleaseInstruction);
  }

  @Override
  protected void onOutputFormatChanged(Format format, @Nullable MediaFormat mediaFormat) {
    super.onOutputFormatChanged(format, mediaFormat);
    if (registeredVideoSink == null || mediaFormat == null) {
      return;
    }
    VideoSize size = getDecodedVideoSize(format, mediaFormat);
    if (size.width <= 0
        || size.height <= 0
        || (size.width == registeredWidth && size.height == registeredHeight)) {
      return;
    }
    // In-stream ABR switches do not set media3's pending sink stream change flag.
    changeVideoSinkInputStream(
        registeredVideoSink,
        VideoSink.INPUT_TYPE_SURFACE,
        format
            .buildUpon()
            .setWidth(size.width)
            .setHeight(size.height)
            .setPixelWidthHeightRatio(size.pixelWidthHeightRatio)
            .build(),
        VideoSink.RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED);
  }

  static VideoSize getDecodedVideoSize(Format format, MediaFormat mediaFormat) {
    boolean hasCrop =
        mediaFormat.containsKey("crop-left")
            && mediaFormat.containsKey("crop-right")
            && mediaFormat.containsKey("crop-top")
            && mediaFormat.containsKey("crop-bottom");
    int width =
        hasCrop
            ? mediaFormat.getInteger("crop-right") - mediaFormat.getInteger("crop-left") + 1
            : mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
    int height =
        hasCrop
            ? mediaFormat.getInteger("crop-bottom") - mediaFormat.getInteger("crop-top") + 1
            : mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
    float pixelWidthHeightRatio = format.pixelWidthHeightRatio;
    if (format.rotationDegrees == 90 || format.rotationDegrees == 270) {
      int unrotatedWidth = width;
      width = height;
      height = unrotatedWidth;
      pixelWidthHeightRatio = 1 / pixelWidthHeightRatio;
    }
    return new VideoSize(width, height, pixelWidthHeightRatio);
  }
}
