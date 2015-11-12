package com.mapswithme.maps.routing;

import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import com.mapswithme.country.ActiveCountryTree;
import com.mapswithme.country.StorageOptions;
import com.mapswithme.maps.*;
import com.mapswithme.maps.bookmarks.data.MapObject;
import com.mapswithme.maps.location.LocationHelper;
import com.mapswithme.util.Config;
import com.mapswithme.util.Utils;
import com.mapswithme.util.concurrency.UiThread;

import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@android.support.annotation.UiThread
public class RoutingController
{
  private static final String TAG = "RCSTATE";

  private enum State
  {
    NONE,
    PREPARE,
    NAVIGATION
  }

  public enum BuildState
  {
    NONE,
    BUILDING,
    BUILT,
    ERROR
  }

  public interface Container
  {
    FragmentActivity getActivity();
    void showSearch(boolean showMyPosition);
    void showPlan(boolean show);
    void showNavigation(boolean show);
    void showDownloader(boolean openDownloadedList);
    void updatePoints();

    /**
     * @param progress [0..100] for progress to be displayed.
     * @param router selected router type. One of {@link Framework#ROUTER_TYPE_VEHICLE} and {@link Framework#ROUTER_TYPE_PEDESTRIAN}.
     * */
    void updateBuildProgress(int progress, int router);
  }

  private static final RoutingController sInstance = new RoutingController();

  private Container mContainer;
  private View mStartButton;

  private BuildState mBuildState = BuildState.NONE;
  private State mState = State.NONE;

  private MapObject mStartPoint;
  private MapObject mEndPoint;

  private int mLastBuildProgress;
  private int mLastRouterType = Framework.nativeGetLastUsedRouter();

  @SuppressWarnings("FieldCanBeLocal")
  private final Framework.RoutingListener mRoutingListener = new Framework.RoutingListener()
  {
    @Override
    public void onRoutingEvent(final int resultCode, final MapStorage.Index[] missingCountries, final MapStorage.Index[] missingRoutes)
    {
      Log.d(TAG, "onRoutingEvent(resultCode: " + resultCode + ")");

      UiThread.run(new Runnable()
      {
        @Override
        public void run()
        {
          if (resultCode == RoutingResultCodesProcessor.NO_ERROR)
          {
            mBuildState = BuildState.BUILT;
            mLastBuildProgress = 100;
            updatePlan();
            return;
          }

          if (mContainer == null)
            return;

          mBuildState = BuildState.ERROR;
          mLastBuildProgress = 0;
          updateProgress();

          Bundle args = new Bundle();
          args.putInt(RoutingErrorDialogFragment.EXTRA_RESULT_CODE, resultCode);
          args.putSerializable(RoutingErrorDialogFragment.EXTRA_MISSING_COUNTRIES, missingCountries);
          args.putSerializable(RoutingErrorDialogFragment.EXTRA_MISSING_ROUTES, missingRoutes);
          RoutingErrorDialogFragment fragment = (RoutingErrorDialogFragment) Fragment.instantiate(MwmApplication.get(),
                                                                                                  RoutingErrorDialogFragment.class.getName());
          fragment.setArguments(args);
          fragment.setListener(new RoutingErrorDialogFragment.Listener()
          {
            @Override
            public void onDownload()
            {
              cancel();

              if (missingCountries != null && missingCountries.length != 0)
                ActiveCountryTree.downloadMapsForIndex(missingCountries, StorageOptions.MAP_OPTION_MAP_AND_CAR_ROUTING);
              if (missingRoutes != null && missingRoutes.length != 0)
                ActiveCountryTree.downloadMapsForIndex(missingRoutes, StorageOptions.MAP_OPTION_CAR_ROUTING);

              if (mContainer != null)
                mContainer.showDownloader(true);
            }

            @Override
            public void onOk()
            {
              if (RoutingResultCodesProcessor.isDownloadable(resultCode))
              {
                cancel();

                if (mContainer != null)
                  mContainer.showDownloader(false);
              }
            }
          });

          fragment.show(mContainer.getActivity().getSupportFragmentManager(), fragment.getClass().getSimpleName());
        }
      });
    }
  };

  @SuppressWarnings("FieldCanBeLocal")
  private final Framework.RoutingProgressListener mRoutingProgressListener = new Framework.RoutingProgressListener()
  {
    @Override
    public void onRouteBuildingProgress(final float progress)
    {
      UiThread.run(new Runnable()
      {
        @Override
        public void run()
        {
          mLastBuildProgress = (int)progress;
          updateProgress();
        }
      });
    }
  };

  private RoutingController()
  {
    Framework.nativeSetRoutingListener(mRoutingListener);
    Framework.nativeSetRouteProgressListener(mRoutingProgressListener);
  }

  public static RoutingController get()
  {
    return sInstance;
  }

  private void setState(State newState)
  {
    Log.d(TAG, "[S] State: " + mState + " -> " + newState + ", BuildState: " + mBuildState);
    mState = newState;
  }

  private void setBuildState(BuildState newState)
  {
    Log.d(TAG, "[B] State: " + mState + ", BuildState: " + mBuildState + " -> " + newState);
    mBuildState = newState;
  }

  private void restoreAttachedContainer()
  {
    mContainer.showPlan(isPlanning());
    mContainer.showNavigation(isNavigating());
    updatePlan();
  }

  private void updateProgress()
  {
    if (mContainer != null)
      mContainer.updateBuildProgress(mLastBuildProgress, mLastRouterType);
  }

  public void attach(@NonNull Container container)
  {
    Log.d(TAG, "attach");

    if (mContainer != null)
      throw new IllegalStateException("Must be detached before attach()");

    mContainer = container;
    restoreAttachedContainer();
  }

  public void detach()
  {
    Log.d(TAG, "detach");

    mContainer = null;
    mStartButton = null;
  }

  private void build()
  {
    Log.d(TAG, "build");

    mLastBuildProgress = 0;
    setBuildState(BuildState.BUILDING);
    updatePlan();

    Framework.nativeBuildRoute(mStartPoint.getLat(), mStartPoint.getLon(), mEndPoint.getLat(), mEndPoint.getLon());
  }

  private void showDisclaimer(final MapObject endPoint)
  {
    StringBuilder builder = new StringBuilder();
    for (int resId : new int[] { R.string.dialog_routing_disclaimer_priority, R.string.dialog_routing_disclaimer_precision,
                                 R.string.dialog_routing_disclaimer_recommendations, R.string.dialog_routing_disclaimer_beware })
      builder.append(MwmApplication.get().getString(resId)).append("\n\n");

    new AlertDialog.Builder(mContainer.getActivity())
        .setTitle(R.string.dialog_routing_disclaimer_title)
        .setMessage(builder.toString())
        .setCancelable(false)
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener()
        {
          @Override
          public void onClick(DialogInterface dlg, int which)
          {
            Config.acceptRoutingDisclaimer();
            prepare(endPoint);
          }
        }).show();
  }

  public void prepare(@Nullable MapObject endPoint)
  {
    Log.d(TAG, "prepare (" + (endPoint == null ? "route)" : "p2p)"));

    if (!Config.isRoutingDisclaimerAccepted())
    {
      showDisclaimer(endPoint);
      return;
    }

    if (!LocationState.isTurnedOn())
    {
      mRoutingListener.onRoutingEvent(RoutingResultCodesProcessor.NO_POSITION, null, null);
      return;
    }

    mStartPoint = LocationHelper.INSTANCE.getMyPosition();
    if (mStartPoint == null)
    {
      mRoutingListener.onRoutingEvent(RoutingResultCodesProcessor.NO_POSITION, null, null);
      return;
    }

    mEndPoint = endPoint;
    setState(State.PREPARE);

    if (mContainer != null)
      mContainer.showPlan(true);

    if (mEndPoint != null)
      mLastRouterType = Framework.nativeGetBestRouter(mStartPoint.getLat(), mStartPoint.getLon(),
                                                      mEndPoint.getLat(), mEndPoint.getLon());
    Framework.nativeSetRouter(mLastRouterType);

    if (mEndPoint == null)
      updatePlan();
    else
      build();
  }

  public void start()
  {
    Log.d(TAG, "start");

    setState(State.NAVIGATION);
    Framework.nativeFollowRoute();

    if (mContainer != null)
    {
      mContainer.showPlan(false);
      mContainer.showNavigation(true);
    }
  }

  private void updatePlan()
  {
    updateProgress();
    updateStartButton();
  }

  private void updateStartButton()
  {
    Log.d(TAG, "updateStartButton" + (mStartButton == null ? ": SKIP" : ""));

    if (mStartButton == null)
      return;

    mStartButton.setEnabled(mState == State.PREPARE &&
                            mBuildState == BuildState.BUILT);
  }

  public void setStartButton(@NonNull View button)
  {
    Log.d(TAG, "setStartButton");
    mStartButton = button;
    updateStartButton();
  }

  private void cancelInternal()
  {
    mStartPoint = null;
    mEndPoint = null;

    setState(State.NONE);
    setBuildState(BuildState.NONE);

    Framework.nativeCloseRouting();
  }

  public boolean cancel()
  {
    if (isPlanning())
    {
      Log.d(TAG, "cancel: planning");

      cancelInternal();
      if (mContainer != null)
        mContainer.showPlan(false);
      return true;
    }

    if (isNavigating())
    {
      Log.d(TAG, "cancel: navigating");

      cancelInternal();
      if (mContainer != null)
        mContainer.showNavigation(false);
      return true;
    }

    Log.d(TAG, "cancel: none");
    return false;
  }

  // Nothing related to navigation is active
  public boolean isIdle()
  {
    return (mState == State.NONE);
  }

  // Planning UI is visible, no navigation active
  public boolean isPlanning()
  {
    return (mState == State.PREPARE);
  }

  // Navigation is active
  public boolean isNavigating()
  {
    return (mState == State.NAVIGATION);
  }

  // Route building is in progress
  public boolean isBuilding()
  {
    return (mState == State.PREPARE && mBuildState == BuildState.BUILDING);
  }

  public BuildState getBuildState()
  {
    return mBuildState;
  }

  public MapObject getStartPoint()
  {
    return mStartPoint;
  }

  public MapObject getEndPoint()
  {
    return mEndPoint;
  }

  private void checkAndBuildRoute()
  {
    if (mContainer != null)
      mContainer.updatePoints();

    if (mStartPoint != null && mEndPoint != null)
      build();
  }

  private boolean setStartFromMyPosition()
  {
    Log.d(TAG, "setStartFromMyPosition");

    MapObject my = LocationHelper.INSTANCE.getMyPosition();
    if (my == null)
    {
      Log.d(TAG, "setStartFromMyPosition: no my position - skip");
      return false;
    }

    return setStartPoint(my);
  }

  @SuppressWarnings("Duplicates")
  public boolean setStartPoint(MapObject point)
  {
    Log.d(TAG, "setStartPoint");

    if (MapObject.same(mStartPoint, point))
    {
      Log.d(TAG, "setStartPoint: skip the same starting point");
      return false;
    }

    if (point != null && point.equals(mEndPoint))
    {
      if (mStartPoint == null)
      {
        Log.d(TAG, "setStartPoint: skip because starting point is empty");
        return false;
      }

      Log.d(TAG, "setStartPoint: swap with end point");
      mEndPoint = mStartPoint;
    }

    mStartPoint = point;
    checkAndBuildRoute();
    return true;
  }

  @SuppressWarnings("Duplicates")
  public boolean setEndPoint(MapObject point)
  {
    Log.d(TAG, "setEndPoint");

    if (MapObject.same(mEndPoint, point))
    {
      if (mStartPoint == null)
        return setStartFromMyPosition();

      Log.d(TAG, "setEndPoint: skip the same end point");
      return false;
    }

    if (point != null && point.equals(mStartPoint))
    {
      if (mEndPoint == null)
      {
        Log.d(TAG, "setEndPoint: skip because end point is empty");
        return false;
      }

      Log.d(TAG, "setEndPoint: swap with starting point");
      mStartPoint = mEndPoint;
    }

    mEndPoint = point;

    if (mStartPoint == null)
      return setStartFromMyPosition();

    checkAndBuildRoute();
    return true;
  }

  public void setRouterType(int router)
  {
    Log.d(TAG, "setRouterType: " + mLastRouterType + " -> " + router);

    if (router == mLastRouterType)
      return;

    mLastRouterType = router;
    Framework.nativeSetRouter(router);

    if (mStartPoint != null && mEndPoint != null)
      build();
  }

  public void onPoiSelected(MapObject point)
  {

  }

  public static CharSequence formatRoutingTime(int seconds)
  {
    long minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60;
    long hours = TimeUnit.SECONDS.toHours(seconds);
    if (hours == 0 && minutes == 0)
      // One minute is added to estimated time to destination point to prevent displaying zero minutes left
      minutes++;

    return hours == 0 ? Utils.formatUnitsText(R.dimen.text_size_routing_number,
                                              R.dimen.text_size_routing_dimension,
                                              String.valueOf(minutes), "min")
                      : TextUtils.concat(Utils.formatUnitsText(R.dimen.text_size_routing_number,
                                                               R.dimen.text_size_routing_dimension,
                                                               String.valueOf(hours), "h "),
                                         Utils.formatUnitsText(R.dimen.text_size_routing_number,
                                                               R.dimen.text_size_routing_dimension,
                                                               String.valueOf(minutes), "min"));
  }

  public static String formatArrivalTime(int seconds)
  {
    Calendar current = Calendar.getInstance();
    current.add(Calendar.SECOND, seconds);
    return String.format(Locale.US, "%d:%02d", current.get(Calendar.HOUR_OF_DAY), current.get(Calendar.MINUTE));
  }
}
