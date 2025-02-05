/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.drawee.components;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;

/**
 * Component that defers {@code release} until after the main Looper has completed its current
 * message. Although we would like for defer {@code release} to happen immediately after the current
 * message is done, this is not guaranteed as there might be other messages after the current one,
 * but before the deferred one, pending in the Looper's queue.
 *
 * <p>onDetach / onAttach events are used for releasing / acquiring resources. However, sometimes we
 * get an onDetach event followed by an onAttach event within the same loop. In order to avoid
 * overaggressive resource releasing / acquiring, we defer releasing. If onAttach happens within the
 * same loop, we will simply cancel corresponding deferred release, avoiding an unnecessary resource
 * release / acquire cycle. If onAttach doesn't happen before the deferred message gets executed,
 * the resources will be released.
 *
 * <p>This class is thread-safe. For releasables sent from threads without a looper, it's
 * immediately released
 */
public class DeferredReleaserConcurrentImpl extends DeferredReleaser {

  private static final IReleaser IMMEDIATE = new ImmediateImpl();

  // looks like ThreadLocal.get is pretty expensive, there's stat-sig scroll perf regression despite
  // other optimizations. Use a dedicated main releaser
  private final DeferredImpl mainReleaser = new DeferredImpl(Looper.getMainLooper());
  private final ThreadLocal<IReleaser> threadLocalReleaser = new ThreadLocal<>();

  /**
   * Schedules deferred release.
   *
   * <p>The object will be released after the current Looper's loop, unless {@code
   * cancelDeferredRelease} is called before then.
   *
   * @param releasable Object to release.
   */
  @Override
  public void scheduleDeferredRelease(Releasable releasable) {
    getReleaser().release(releasable);
  }

  /**
   * Cancels a pending release for this object.
   *
   * @param releasable Object to cancel release of.
   */
  @Override
  public void cancelDeferredRelease(Releasable releasable) {
    getReleaser().cancel(releasable);
  }

  private IReleaser getReleaser() {
    if (isOnUiThread()) {
      return mainReleaser;
    } else {
      IReleaser releaser = threadLocalReleaser.get();
      if (releaser == null) {
        Looper looper = Looper.myLooper();
        releaser = looper == null ? IMMEDIATE : new DeferredImpl(looper);
        threadLocalReleaser.set(releaser);
      }
      return releaser;
    }
  }

  interface IReleaser {
    void release(Releasable releasable);

    void cancel(Releasable releasable);
  }

  static class DeferredImpl implements IReleaser {

    private final ArrayList<Releasable> mPendingReleasables;
    private final Handler mHandler;

    private final Runnable releaseRunnable =
        new Runnable() {
          @Override
          public void run() {
            //noinspection ForLoopReplaceableByForEach avoid allocation of the iterator
            for (int i = 0, size = mPendingReleasables.size(); i < size; i++) {
              mPendingReleasables.get(i).release();
            }
            mPendingReleasables.clear();
          }
        };

    public DeferredImpl(Looper looper) {
      mPendingReleasables = new ArrayList<>();
      mHandler = new Handler(looper);
    }

    @Override
    public void release(Releasable releasable) {
      if (mPendingReleasables.contains(releasable)) {
        return;
      }
      mPendingReleasables.add(releasable);
      if (mPendingReleasables.size() == 1) {
        mHandler.post(releaseRunnable);
      }
    }

    @Override
    public void cancel(Releasable releasable) {
      mPendingReleasables.remove(releasable);
    }
  }

  static class ImmediateImpl implements IReleaser {

    @Override
    public void release(Releasable releasable) {
      releasable.release();
    }

    @Override
    public void cancel(Releasable releasable) {}
  }
}
