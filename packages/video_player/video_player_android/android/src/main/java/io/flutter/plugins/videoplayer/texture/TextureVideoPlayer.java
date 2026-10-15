// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer.texture;

import android.content.Context;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
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
    MediaItem mediaItem = asset.getMediaItem();
    // Only local files (workouts) get the crop pass; streamed classes skip its cost.
    boolean appliesVideoEffects =
        isLocalSource(mediaItem) && !surfaceProducer.handlesCropAndRotation();
    return new TextureVideoPlayer(
        events,
        surfaceProducer,
        mediaItem,
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
              new ExoPlayer.Builder(context, new ResizingRenderersFactory(context))
                  .setTrackSelector(trackSelector)
                  .setBandwidthMeter(bandwidthMeter)
                  .setMediaSourceFactory(asset.getMediaSourceFactory(context));
          return builder.build();
        },
        appliesVideoEffects);
  }

  @VisibleForTesting
  static boolean isLocalSource(@NonNull MediaItem mediaItem) {
    if (mediaItem.localConfiguration == null) {
      return false;
    }
    String scheme = mediaItem.localConfiguration.uri.getScheme();
    return scheme == null || "file".equals(scheme) || "asset".equals(scheme);
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
    this(
        events,
        surfaceProducer,
        mediaItem,
        options,
        exoPlayerProvider,
        !surfaceProducer.handlesCropAndRotation());
  }

  @OptIn(markerClass = UnstableApi.class)
  private TextureVideoPlayer(
      @NonNull VideoPlayerCallbacks events,
      @NonNull SurfaceProducer surfaceProducer,
      @NonNull MediaItem mediaItem,
      @NonNull VideoPlayerOptions options,
      @NonNull ExoPlayerProvider exoPlayerProvider,
      boolean appliesVideoEffects) {
    super(
        events,
        mediaItem,
        options,
        surfaceProducer,
        withVideoEffects(exoPlayerProvider, appliesVideoEffects));

    surfaceProducer.setCallback(this);
    if (appliesVideoEffects) {
      // media3 reports no video size while effects are set; use the Format.
      this.exoPlayer.addAnalyticsListener(
          new AnalyticsListener() {
            @OptIn(markerClass = UnstableApi.class)
            @Override
            public void onVideoInputFormatChanged(
                @NonNull EventTime eventTime,
                @NonNull Format format,
                @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
              if (exoPlayer.isReleased()) {
                return;
              }
              boolean swap = format.rotationDegrees == 90 || format.rotationDegrees == 270;
              setOutputSize(
                  swap ? format.height : format.width, swap ? format.width : format.height);
            }
          });
    }

    Surface surface = surfaceProducer.getSurface();
    this.exoPlayer.setVideoSurface(surface);
    needsSurface = surface == null;
  }

  @OptIn(markerClass = UnstableApi.class)
  @NonNull
  private static ExoPlayerProvider withVideoEffects(
      @NonNull ExoPlayerProvider exoPlayerProvider, boolean appliesVideoEffects) {
    return () -> {
      ExoPlayer player = exoPlayerProvider.get();
      if (appliesVideoEffects) {
        // ImageReader surfaces need the GL effect pipeline to apply the decoder crop.
        player.setVideoEffects(
            ImmutableList.of(new ScaleAndRotateTransformation.Builder().build()));
      }
      return player;
    };
  }

  // The effect pipeline needs an explicit output size for a bare Surface.
  void setOutputSize(int width, int height) {
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

  @OptIn(markerClass = UnstableApi.class)
  private void sendOutputResolution() {
    if (outputWidth == 0) {
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
