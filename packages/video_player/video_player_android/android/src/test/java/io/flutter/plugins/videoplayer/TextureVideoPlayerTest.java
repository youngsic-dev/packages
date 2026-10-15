// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import android.view.Surface;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.PlayerMessage;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import io.flutter.plugins.videoplayer.texture.TextureVideoPlayer;
import io.flutter.view.TextureRegistry;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for {@link TextureVideoPlayer}.
 *
 * <p>This test suite <em>narrowly verifies</em> that {@link TextureVideoPlayer} interfaces with the
 * {@link ExoPlayer} interface <em>exactly</em> as it did when the test suite was created. That is,
 * if the behavior changes, this test will need to change. However, this suite should catch bugs
 * related to <em>"this is a safe refactor with no behavior changes"</em>.
 */
@OptIn(markerClass = UnstableApi.class)
@RunWith(RobolectricTestRunner.class)
public final class TextureVideoPlayerTest {
  private static final String FAKE_ASSET_URL = "https://flutter.dev/movie.mp4";
  private FakeVideoAsset fakeVideoAsset;

  @Mock private VideoPlayerCallbacks mockEvents;
  @Mock private TextureRegistry.SurfaceProducer mockProducer;
  @Mock private ExoPlayer mockExoPlayer;
  @Mock private Renderer mockRenderer;
  @Mock private PlayerMessage mockPlayerMessage;
  @Mock private AnalyticsListener.EventTime mockEventTime;
  @Captor private ArgumentCaptor<AudioAttributes> attributesCaptor;
  @Captor private ArgumentCaptor<TextureRegistry.SurfaceProducer.Callback> callbackCaptor;
  @Captor private ArgumentCaptor<Player.Listener> listenerCaptor;
  @Captor private ArgumentCaptor<AnalyticsListener> analyticsListenerCaptor;

  @Rule public MockitoRule initRule = MockitoJUnit.rule();

  @Before
  public void setUp() {
    fakeVideoAsset = new FakeVideoAsset(FAKE_ASSET_URL);
    when(mockProducer.getSurface()).thenReturn(mock(Surface.class));
    when(mockProducer.handlesCropAndRotation()).thenReturn(true);
  }

  private VideoPlayer createVideoPlayer() {
    return createVideoPlayer(new VideoPlayerOptions());
  }

  private TextureVideoPlayer createVideoPlayer(VideoPlayerOptions options) {
    return new TextureVideoPlayer(
        mockEvents, mockProducer, fakeVideoAsset.getMediaItem(), options, () -> mockExoPlayer);
  }

  private void setUpVideoEffects() {
    when(mockProducer.handlesCropAndRotation()).thenReturn(false);
    when(mockExoPlayer.getRendererCount()).thenReturn(1);
    when(mockExoPlayer.getRendererType(0)).thenReturn(C.TRACK_TYPE_VIDEO);
    when(mockExoPlayer.getRenderer(0)).thenReturn(mockRenderer);
    when(mockExoPlayer.createMessage(any())).thenReturn(mockPlayerMessage);
    when(mockPlayerMessage.setType(anyInt())).thenReturn(mockPlayerMessage);
    when(mockPlayerMessage.setPayload(any())).thenReturn(mockPlayerMessage);
  }

  private AnalyticsListener captureAnalyticsListener() {
    verify(mockExoPlayer).addAnalyticsListener(analyticsListenerCaptor.capture());
    return analyticsListenerCaptor.getValue();
  }

  @Test
  public void setsVideoEffectsBeforePrepare() {
    setUpVideoEffects();
    VideoPlayer videoPlayer = createVideoPlayer();
    captureAnalyticsListener();

    InOrder inOrder = inOrder(mockExoPlayer);
    inOrder.verify(mockExoPlayer).setVideoEffects(any());
    inOrder.verify(mockExoPlayer).prepare();

    videoPlayer.dispose();
  }

  @Test
  public void onVideoInputFormatChangedSetsSizeAndSurfaceBeforeOutputResolution() {
    setUpVideoEffects();
    VideoPlayer videoPlayer = createVideoPlayer();
    AnalyticsListener listener = captureAnalyticsListener();
    Surface resizedSurface = mock(Surface.class);
    when(mockProducer.getSurface()).thenReturn(resizedSurface);
    Format format = new Format.Builder().setWidth(1080).setHeight(1500).build();

    listener.onVideoInputFormatChanged(mockEventTime, format, null);

    InOrder inOrder = inOrder(mockProducer, mockExoPlayer, mockPlayerMessage);
    inOrder.verify(mockProducer).setSize(1080, 1500);
    inOrder.verify(mockExoPlayer).setVideoSurface(resizedSurface);
    inOrder.verify(mockExoPlayer).createMessage(mockRenderer);
    inOrder.verify(mockPlayerMessage).setType(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION);
    inOrder.verify(mockPlayerMessage).setPayload(new Size(1080, 1500));
    inOrder.verify(mockPlayerMessage).send();

    videoPlayer.dispose();
  }

  @Test
  public void onVideoInputFormatChangedSwapsRotatedDimensions() {
    setUpVideoEffects();
    VideoPlayer videoPlayer = createVideoPlayer();
    AnalyticsListener listener = captureAnalyticsListener();
    Format format =
        new Format.Builder().setWidth(1500).setHeight(1080).setRotationDegrees(90).build();

    listener.onVideoInputFormatChanged(mockEventTime, format, null);

    verify(mockProducer).setSize(1080, 1500);
    verify(mockPlayerMessage).setPayload(new Size(1080, 1500));

    videoPlayer.dispose();
  }

  @Test
  public void onVideoInputFormatChangedWithSameSizeDoesNothing() {
    setUpVideoEffects();
    VideoPlayer videoPlayer = createVideoPlayer();
    AnalyticsListener listener = captureAnalyticsListener();
    Format format = new Format.Builder().setWidth(1080).setHeight(1500).build();

    listener.onVideoInputFormatChanged(mockEventTime, format, null);
    listener.onVideoInputFormatChanged(mockEventTime, format, null);

    verify(mockProducer).setSize(1080, 1500);
    verify(mockExoPlayer).createMessage(mockRenderer);
    verify(mockPlayerMessage).setPayload(new Size(1080, 1500));
    verify(mockPlayerMessage).send();

    videoPlayer.dispose();
  }

  @Test
  public void onVideoInputFormatChangedDefersOutputResolutionUntilSurfaceAvailable() {
    setUpVideoEffects();
    when(mockProducer.getSurface()).thenReturn(null);
    TextureVideoPlayer videoPlayer = createVideoPlayer(new VideoPlayerOptions());
    AnalyticsListener listener = captureAnalyticsListener();
    Format format = new Format.Builder().setWidth(1080).setHeight(1500).build();

    listener.onVideoInputFormatChanged(mockEventTime, format, null);

    verify(mockProducer).setSize(1080, 1500);
    verify(mockExoPlayer, never()).createMessage(any());
    verifyNoInteractions(mockPlayerMessage);

    Surface availableSurface = mock(Surface.class);
    when(mockProducer.getSurface()).thenReturn(availableSurface);
    videoPlayer.onSurfaceAvailable();

    InOrder inOrder = inOrder(mockExoPlayer, mockPlayerMessage);
    inOrder.verify(mockExoPlayer).setVideoSurface(availableSurface);
    inOrder.verify(mockExoPlayer).createMessage(mockRenderer);
    inOrder.verify(mockPlayerMessage).setType(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION);
    inOrder.verify(mockPlayerMessage).setPayload(new Size(1080, 1500));
    inOrder.verify(mockPlayerMessage).send();

    videoPlayer.dispose();
  }

  @Test
  public void onVideoInputFormatChangedAfterDisposeDoesNotSetSize() {
    setUpVideoEffects();
    VideoPlayer videoPlayer = createVideoPlayer();
    AnalyticsListener listener = captureAnalyticsListener();
    Format format = new Format.Builder().setWidth(1080).setHeight(1500).build();

    videoPlayer.dispose();
    when(mockExoPlayer.isReleased()).thenReturn(true);
    listener.onVideoInputFormatChanged(mockEventTime, format, null);

    verify(mockProducer, never()).setSize(anyInt(), anyInt());
    verify(mockExoPlayer, never()).createMessage(any());
  }

  @Test
  public void surfaceProducerHandlingCropAndRotationDoesNotUseVideoEffects() {
    when(mockProducer.handlesCropAndRotation()).thenReturn(true);
    VideoPlayer videoPlayer = createVideoPlayer();

    verify(mockExoPlayer, never()).setVideoEffects(any());
    verify(mockExoPlayer, never()).addAnalyticsListener(any());

    videoPlayer.dispose();
  }

  @Test
  public void loadsAndPreparesProvidedMediaEnablesAudioFocusByDefault() {
    VideoPlayer videoPlayer = createVideoPlayer();

    verify(mockExoPlayer).setMediaItem(fakeVideoAsset.getMediaItem());
    verify(mockExoPlayer).prepare();
    verify(mockProducer).getSurface();
    verify(mockExoPlayer).setVideoSurface(any());

    verify(mockExoPlayer).setAudioAttributes(attributesCaptor.capture(), eq(true));
    assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, attributesCaptor.getValue().contentType);

    videoPlayer.dispose();
  }

  @Test
  public void onSurfaceProducerCleanupAndAvailableRemovesAndRestoresSurface() {
    VideoPlayer videoPlayer = createVideoPlayer();

    verify(mockProducer).setCallback(callbackCaptor.capture());
    verify(mockExoPlayer).setVideoSurface(notNull());
    verify(mockExoPlayer, never()).release();

    when(mockExoPlayer.getCurrentPosition()).thenReturn(10L);
    when(mockExoPlayer.getRepeatMode()).thenReturn(Player.REPEAT_MODE_ALL);
    when(mockExoPlayer.getVolume()).thenReturn(0.5f);
    when(mockExoPlayer.getPlaybackParameters()).thenReturn(new PlaybackParameters(2.5f));

    TextureRegistry.SurfaceProducer.Callback producerLifecycle = callbackCaptor.getValue();
    producerLifecycle.onSurfaceCleanup();

    verify(mockExoPlayer).setVideoSurface(null);

    reset(mockExoPlayer);
    producerLifecycle.onSurfaceAvailable();

    verify(mockExoPlayer).setVideoSurface(notNull());

    videoPlayer.dispose();
  }

  @Test
  public void onSurfaceProducerCleanupDoesNotStopOrPauseVideo() {
    VideoPlayer videoPlayer = createVideoPlayer();

    verify(mockProducer).setCallback(callbackCaptor.capture());
    TextureRegistry.SurfaceProducer.Callback producerLifecycle = callbackCaptor.getValue();
    producerLifecycle.onSurfaceCleanup();

    verify(mockExoPlayer, never()).stop();
    verify(mockExoPlayer, never()).pause();
    verify(mockExoPlayer, never()).setPlayWhenReady(anyBoolean());

    videoPlayer.dispose();
  }

  @Test
  public void onInitializedCalledWhenVideoPlayerInitiallyCreated() {
    VideoPlayer videoPlayer = createVideoPlayer();

    // Pretend we have a video, and capture the registered event listener.
    when(mockExoPlayer.getVideoSize()).thenReturn(new VideoSize(300, 200));
    verify(mockExoPlayer).addListener(listenerCaptor.capture());
    Player.Listener listener = listenerCaptor.getValue();

    // Trigger an event that would trigger onInitialized.
    listener.onPlaybackStateChanged(Player.STATE_READY);
    verify(mockEvents).onInitialized(anyInt(), anyInt(), anyLong(), anyInt());

    videoPlayer.dispose();
  }

  @Test
  public void onSurfaceAvailableDoesNotSendInitializeEventAgain() {
    // The VideoPlayer contract assumes that the event "initialized" is sent exactly once
    // (duplicate events cause an error to be thrown at the shared Dart layer). This test verifies
    // that the onInitialized event is sent exactly once per player.
    //
    // Regression test for https://github.com/flutter/flutter/issues/154602.
    VideoPlayer videoPlayer = createVideoPlayer();
    when(mockExoPlayer.getVideoSize()).thenReturn(new VideoSize(300, 200));

    // Capture the lifecycle events so we can simulate onSurfaceAvailable/Cleanup.
    verify(mockProducer).setCallback(callbackCaptor.capture());
    TextureRegistry.SurfaceProducer.Callback producerLifecycle = callbackCaptor.getValue();

    verify(mockExoPlayer).addListener(listenerCaptor.capture());
    Player.Listener listener = listenerCaptor.getValue();

    // Trigger ready state, which should trigger initialization.
    listener.onPlaybackStateChanged(Player.STATE_READY);
    verify(mockEvents).onInitialized(anyInt(), anyInt(), anyLong(), anyInt());

    // Trigger cleanup/available.
    producerLifecycle.onSurfaceCleanup();
    producerLifecycle.onSurfaceAvailable();

    // It should still only have been called the one time.
    verify(mockEvents).onInitialized(anyInt(), anyInt(), anyLong(), anyInt());

    videoPlayer.dispose();
  }

  @Test
  public void onSurfaceAvailableWithoutCleanupDoesNotRecreate() {
    // Initially create the video player, which creates the initial surface.
    VideoPlayer videoPlayer = createVideoPlayer();
    verify(mockProducer).getSurface();
    verify(mockProducer).handlesCropAndRotation();

    // Capture the lifecycle events so we can simulate onSurfaceAvailable/Cleanup.
    verify(mockProducer).setCallback(callbackCaptor.capture());
    TextureRegistry.SurfaceProducer.Callback producerLifecycle = callbackCaptor.getValue();

    // Calling onSurfaceAvailable does not do anything, since the surface was never cleaned up.
    producerLifecycle.onSurfaceAvailable();
    verifyNoMoreInteractions(mockProducer);

    videoPlayer.dispose();
  }

  @Test
  public void disposeReleasesExoPlayerBeforeTexture() {
    VideoPlayer videoPlayer = createVideoPlayer();

    videoPlayer.dispose();

    // Regression test for https://github.com/flutter/flutter/issues/156158.
    // The player must be destroyed before the surface it is writing to.
    InOrder inOrder = inOrder(mockExoPlayer, mockProducer);
    inOrder.verify(mockExoPlayer).release();
    inOrder.verify(mockProducer).release();
  }
}
