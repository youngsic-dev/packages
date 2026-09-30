// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer.texture;

import android.content.Context;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import com.google.common.collect.ImmutableList;
import io.flutter.plugins.videoplayer.ExoPlayerEventListener;
import io.flutter.plugins.videoplayer.VideoAsset;
import io.flutter.plugins.videoplayer.VideoPlayer;
import io.flutter.plugins.videoplayer.VideoPlayerCallbacks;
import io.flutter.plugins.videoplayer.VideoPlayerOptions;
import io.flutter.view.TextureRegistry.SurfaceProducer;

/**
 * A subclass of {@link VideoPlayer} that adds functionality related to texture view as a way of
 * displaying the video in the app.
 *
 * <p>It manages the lifecycle of the texture and ensures that the video is properly displayed on
 * the texture.
 */
public final class TextureVideoPlayer extends VideoPlayer implements SurfaceProducer.Callback {
  // True when the ExoPlayer instance has a null surface.
  private boolean needsSurface = true;
  private final boolean appliesVideoEffects;
  private int outputWidth;
  private int outputHeight;

  /**
   * Creates a texture video player.
   *
   * @param context application context.
   * @param events event callbacks.
   * @param surfaceProducer produces a texture to render to.
   * @param asset asset to play.
   * @param options options for playback.
   * @return a video player instance.
   */
  // TODO: Migrate to stable API, see https://github.com/flutter/flutter/issues/147039.
  @UnstableApi
  @NonNull
  public static TextureVideoPlayer create(
      @NonNull Context context,
      @NonNull VideoPlayerCallbacks events,
      @NonNull SurfaceProducer surfaceProducer,
      @NonNull VideoAsset asset,
      @NonNull VideoPlayerOptions options) {
    return new TextureVideoPlayer(
        events,
        surfaceProducer,
        asset.getMediaItem(),
        options,
        () -> {
          androidx.media3.exoplayer.trackselection.DefaultTrackSelector trackSelector =
              new androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context);
          // Magic fork: wired mirrors open on the top HLS rung immediately instead of
          // ramping from Media3's low WiFi-class initial estimate; WiFi keeps default.
          androidx.media3.exoplayer.upstream.DefaultBandwidthMeter bandwidthMeter =
              new androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(context)
                  .setInitialBitrateEstimate(
                      androidx.media3.common.C.NETWORK_TYPE_ETHERNET, 20_000_000L)
                  .build();
          ExoPlayer.Builder builder =
              new ExoPlayer.Builder(context)
                  .setTrackSelector(trackSelector)
                  .setBandwidthMeter(bandwidthMeter)
                  .setMediaSourceFactory(asset.getMediaSourceFactory(context));
          return builder.build();
        });
  }

  // TODO: Migrate to stable API, see https://github.com/flutter/flutter/issues/147039.
  @UnstableApi
  @VisibleForTesting
  public TextureVideoPlayer(
      @NonNull VideoPlayerCallbacks events,
      @NonNull SurfaceProducer surfaceProducer,
      @NonNull MediaItem mediaItem,
      @NonNull VideoPlayerOptions options,
      @NonNull ExoPlayerProvider exoPlayerProvider) {
    super(events, mediaItem, options, surfaceProducer, exoPlayerProvider);

    surfaceProducer.setCallback(this);
    // ImageReader surfaces draw the decoder's padded buffer; the GL effect pipeline applies crop.
    appliesVideoEffects = !surfaceProducer.handlesCropAndRotation();
    if (appliesVideoEffects) {
      this.exoPlayer.setVideoEffects(
          ImmutableList.of(new ScaleAndRotateTransformation.Builder().build()));
      // onVideoSizeChanged only fires once a frame is rendered, which needs the size first.
      this.exoPlayer.addAnalyticsListener(
          new AnalyticsListener() {
            @Override
            public void onVideoInputFormatChanged(
                @NonNull EventTime eventTime,
                @NonNull Format format,
                @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
              boolean swap = format.rotationDegrees == 90 || format.rotationDegrees == 270;
              setOutputSize(swap ? format.height : format.width, swap ? format.width : format.height);
            }
          });
    }

    Surface surface = surfaceProducer.getSurface();
    this.exoPlayer.setVideoSurface(surface);
    needsSurface = surface == null;
  }

  /** The effect pipeline drops frames until it knows the output size, which a bare Surface lacks. */
  private void setOutputSize(int width, int height) {
    if (width <= 0 || height <= 0 || (width == outputWidth && height == outputHeight)) {
      return;
    }
    outputWidth = width;
    outputHeight = height;
    assert surfaceProducer != null;
    surfaceProducer.setSize(width, height);
    if (!needsSurface) {
      // setSize replaces the ImageReader, so hand the player the new surface.
      exoPlayer.setVideoSurface(surfaceProducer.getSurface());
      sendOutputResolution();
    }
  }

  private void sendOutputResolution() {
    if (!appliesVideoEffects || outputWidth == 0) {
      return;
    }
    Size size = new Size(outputWidth, outputHeight);
    for (int i = 0; i < exoPlayer.getRendererCount(); i++) {
      if (exoPlayer.getRendererType(i) == C.TRACK_TYPE_VIDEO) {
        exoPlayer
            .createMessage(exoPlayer.getRenderer(i))
            .setType(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION)
            .setPayload(size)
            .send();
      }
    }
  }

  @NonNull
  @Override
  protected ExoPlayerEventListener createExoPlayerEventListener(
      @NonNull ExoPlayer exoPlayer, @Nullable SurfaceProducer surfaceProducer) {
    if (surfaceProducer == null) {
      throw new IllegalArgumentException(
          "surfaceProducer cannot be null to create an ExoPlayerEventListener for"
              + " TextureVideoPlayer.");
    }
    // The SurfaceTexture or, on ImageReader, the video effect pipeline applies crop and rotation.
    return new TextureExoPlayerEventListener(exoPlayer, videoPlayerEvents, true);
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY)
  public void onSurfaceAvailable() {
    if (needsSurface) {
      // TextureVideoPlayer must always set a surfaceProducer.
      assert surfaceProducer != null;
      exoPlayer.setVideoSurface(surfaceProducer.getSurface());
      needsSurface = false;
      sendOutputResolution();
    }
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY)
  public void onSurfaceCleanup() {
    exoPlayer.setVideoSurface(null);
    needsSurface = true;
  }

  public void dispose() {
    // Super must be called first to ensure the player is released before the surface.
    super.dispose();

    // TextureVideoPlayer must always set a surfaceProducer.
    assert surfaceProducer != null;
    surfaceProducer.release();
  }
}
