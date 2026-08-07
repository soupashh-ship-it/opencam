package io.opencam.webcam;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Global application object; gives the rest of the code a Context + main looper. */
public class App extends Application {

    private static App instance;
    private static Handler mainHandler;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Context context() {
        return instance.getApplicationContext();
    }

    public static Handler mainHandler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }
}
