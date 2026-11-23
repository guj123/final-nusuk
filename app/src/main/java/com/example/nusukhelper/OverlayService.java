package com.example.nusukhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

public class OverlayService extends Service {
    private WindowManager wm;
    private LinearLayout root;
    private TextView tv;
    private Button autoBtn;
    private WindowManager.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String statusLine = "";

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("nh", "Nusuk Helper", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "nh")
                .setContentTitle("Nusuk Helper running")
                .setSmallIcon(android.R.drawable.ic_menu_edit).build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildView();
        Codes.setDir(getSharedPreferences("nh", MODE_PRIVATE).getString("dir", Codes.DEFAULT_DIR));
        try { Codes.load(); } catch (IOException e) {
            Toast.makeText(this, "File error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        NusukAccessService.listener = s -> ui.post(() -> { statusLine = s; refresh(); if (!auto()) autoBtn.setText("Start"); });
        refresh();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private boolean auto() { return NusukAccessService.instance != null && NusukAccessService.instance.isRunning(); }

    private void buildView() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xE6202020);
        root.setPadding(dp(8), dp(6), dp(8), dp(6));
        tv = new TextView(this);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.MONOSPACE);
        root.addView(tv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        autoBtn = button("Start", v -> toggleAuto());
        row.addView(autoBtn);
        row.addView(button("X", v -> stopSelf()));
        root.addView(row);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(8);
        lp.y = dp(120);
        tv.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy; int sx, sy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: dx = e.getRawX(); dy = e.getRawY(); sx = lp.x; sy = lp.y; return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = sx + (int) (e.getRawX() - dx); lp.y = sy + (int) (e.getRawY() - dy);
                        wm.updateViewLayout(root, lp); return true;
                }
                return false;
            }
        });
        wm.addView(root, lp);
    }

    private Button button(String t, View.OnClickListener c) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false); b.setMinWidth(0); b.setMinimumWidth(dp(52));
        b.setOnClickListener(c);
        return b;
    }

    private void toggleAuto() {
        NusukAccessService s = NusukAccessService.instance;
        if (s == null) {
            Toast.makeText(this, "Enable the Nusuk Helper accessibility service first", Toast.LENGTH_LONG).show();
            return;
        }
        if (s.isRunning()) { s.stopAuto(); autoBtn.setText("Start"); }
        else { s.startAuto(); autoBtn.setText("Stop"); }
    }

    private void refresh() {
        String c = Codes.current();
        String head = c == null ? "All codes done" : c + "  (" + Codes.size() + " left)";
        tv.setText(statusLine.isEmpty() ? head : head + "\n" + statusLine);
    }

    @Override
    public void onDestroy() {
        NusukAccessService.listener = null;
        NusukAccessService s = NusukAccessService.instance;
        if (s != null) s.stopAuto();
        if (root != null) wm.removeView(root);
        super.onDestroy();
    }
}
