package com.vwww.mira;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;

public final class MiraApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                registerVisibleActivity(activity);
            }

            @Override
            public void onActivityStarted(Activity activity) {
                registerVisibleActivity(activity);
            }

            @Override
            public void onActivityResumed(Activity activity) {
                registerVisibleActivity(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                MiraOutlineCollector.getInstance().unregister(activity);
            }
        });
    }

    private void registerVisibleActivity(Activity activity) {
        MiraOutlineCollector.getInstance().register(activity);
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor != null) decor.postDelayed(MiraDiscoveryService::requestOutlineUpload, 160);
    }
}
