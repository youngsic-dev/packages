// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer.texture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.test.core.app.ApplicationProvider;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@OptIn(markerClass = UnstableApi.class)
@RunWith(RobolectricTestRunner.class)
public final class ResizingRenderersFactoryTest {
  @Test
  public void buildsResizingVideoRenderer() {
    ArrayList<Renderer> renderers = new ArrayList<>();

    buildVideoRenderers(renderers);

    assertEquals(1, renderers.size());
    assertTrue(renderers.get(0) instanceof ResizingVideoRenderer);
  }

  @Test
  public void onlyReplacesRendererInNewlyAddedRange() {
    Context context = ApplicationProvider.getApplicationContext();
    MediaCodecVideoRenderer existingRenderer = new MediaCodecVideoRenderer.Builder(context).build();
    ArrayList<Renderer> renderers = new ArrayList<>();
    renderers.add(existingRenderer);

    buildVideoRenderers(renderers);

    assertEquals(2, renderers.size());
    assertSame(existingRenderer, renderers.get(0));
    assertTrue(renderers.get(1) instanceof ResizingVideoRenderer);
  }

  private static void buildVideoRenderers(ArrayList<Renderer> renderers) {
    Context context = ApplicationProvider.getApplicationContext();
    new ResizingRenderersFactory(context)
        .buildVideoRenderers(
            context,
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
            MediaCodecSelector.DEFAULT,
            false,
            new Handler(Looper.getMainLooper()),
            mock(VideoRendererEventListener.class),
            DefaultRenderersFactory.DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS,
            renderers);
  }
}
