// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer.texture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.MediaItem;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class TextureVideoPlayerSourceTest {
  @Test
  public void fileAndAssetUrisAreLocal() {
    assertTrue(TextureVideoPlayer.isLocalSource(MediaItem.fromUri("file:///data/user/0/v.mp4")));
    assertTrue(TextureVideoPlayer.isLocalSource(MediaItem.fromUri("asset:///assets/v.mp4")));
    assertTrue(TextureVideoPlayer.isLocalSource(MediaItem.fromUri("/data/user/0/v.mp4")));
  }

  @Test
  public void streamedUrisAreNotLocal() {
    assertFalse(TextureVideoPlayer.isLocalSource(MediaItem.fromUri("https://cdn.example/v.m3u8")));
    assertFalse(TextureVideoPlayer.isLocalSource(MediaItem.fromUri("http://cdn.example/v.mp4")));
    assertFalse(TextureVideoPlayer.isLocalSource(MediaItem.fromUri("rtsp://cam.example/live")));
  }
}
