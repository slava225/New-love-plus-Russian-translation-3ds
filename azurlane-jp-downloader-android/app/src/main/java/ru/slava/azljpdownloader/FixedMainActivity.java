package ru.slava.azljpdownloader;

import android.os.Bundle;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Compatibility activity that refreshes the UI when Shizuku's binder arrives.
 * The provider connection is asynchronous, so checking pingBinder() only once
 * in MainActivity.onCreate() can incorrectly show "not running" even when
 * Shizuku is already active.
 */
public class FixedMainActivity extends MainActivity {
    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            () -> runOnUiThread(this::refreshCompat);

    private final Shizuku.OnBinderDeadListener binderDeadListener =
            () -> runOnUiThread(this::refreshCompat);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        refreshCompat();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also refresh after returning from Shizuku's permission screen.
        getWindow().getDecorView().postDelayed(this::refreshCompat, 250);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        super.onDestroy();
    }

    private void refreshCompat() {
        try {
            Method m = MainActivity.class.getDeclaredMethod("refreshShizukuState");
            m.setAccessible(true);
            m.invoke(this);
        } catch (Throwable ignored) {
            // MainActivity already performed its normal state check.
        }
    }
}
