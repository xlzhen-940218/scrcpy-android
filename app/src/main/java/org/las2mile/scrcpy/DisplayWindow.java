package org.las2mile.scrcpy;

import android.content.Context;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class DisplayWindow extends FrameLayout {
    private static final String TAG = "DisplayWindow";

    public static final int ACTION_BACK = 0;
    public static final int ACTION_HOME = 1;
    public static final int ACTION_MENU = 2;
    public static final int ACTION_ROTATE = 3;

    private OnClickListener closeListener;
    private OnMoveCallback moveCallback;
    private OnActionCallback actionCallback;
    private OnTouchListener onDisplayTouchListener;
    private SurfaceReadyCallback surfaceReadyCallback;

    private float oldX;
    private float oldY;

    private ViewGroup header;
    private ViewGroup container;
    private SurfaceView surfaceView;
    private ViewGroup actionbar;
    private View resizeHandle;
    private View hintLayout;

    private int remoteW = 1080;
    private int remoteH = 1920;
    private Surface surface;

    public DisplayWindow(Context context) {
        super(context);
        init();
    }

    public DisplayWindow(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DisplayWindow(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClipChildren(false);
        setClipToPadding(false);
        LayoutInflater.from(getContext()).inflate(R.layout.window_display, this, true);

        container = findViewById(R.id.container);
        surfaceView = findViewById(R.id.surface);
        actionbar = findViewById(R.id.actionbar);
        header = findViewById(R.id.header);
        hintLayout = findViewById(R.id.hint_layout);

        // Header drag-to-move
        header.setOnTouchListener((view, motionEvent) -> {
            final int action = motionEvent.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    oldX = motionEvent.getRawX();
                    oldY = motionEvent.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float disX = motionEvent.getRawX() - oldX;
                    float disY = motionEvent.getRawY() - oldY;
                    if (moveCallback != null) {
                        moveCallback.onMove(disX, disY);
                    }
                    oldX = motionEvent.getRawX();
                    oldY = motionEvent.getRawY();
                    break;
            }
            return true;
        });

        // Corner resize handle (manual drag-to-scale)
        resizeHandle = findViewById(R.id.iv_resize);
        if (resizeHandle != null) {
            resizeHandle.setOnTouchListener(new OnTouchListener() {
                private float startRawX;
                private float startRawY;
                private int startW;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startRawX = event.getRawX();
                            startRawY = event.getRawY();
                            startW = container.getWidth();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - startRawX;
                            float dy = event.getRawY() - startRawY;
                            float delta = Math.abs(dx) > Math.abs(dy) ? dx : dy;
                            int targetW = startW + (int) delta;
                            applyConstrainedSize(targetW);
                            return true;
                    }
                    return false;
                }
            });
        }

        // Quick rotate remote device
        View rotateBtn = findViewById(R.id.iv_rotate);
        if (rotateBtn != null) {
            rotateBtn.setOnClickListener(v -> {
                if (actionCallback != null) {
                    actionCallback.onAction(ACTION_ROTATE);
                }
            });
        }

        // Minimize / collapse toggle
        View miniBtn = findViewById(R.id.iv_mini);
        if (miniBtn != null) {
            miniBtn.setOnClickListener(v -> {
                if (container.getVisibility() == View.VISIBLE) {
                    container.setVisibility(View.GONE);
                    actionbar.setVisibility(View.GONE);
                } else {
                    container.setVisibility(View.VISIBLE);
                    actionbar.setVisibility(View.VISIBLE);
                }
            });
        }

        // Close button
        View closeBtn = findViewById(R.id.iv_close);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> {
                if (closeListener != null) {
                    closeListener.onClick(v);
                }
            });
        }

        // Navigation buttons
        View backBtn = findViewById(R.id.action_back);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> {
                if (actionCallback != null) actionCallback.onAction(ACTION_BACK);
            });
        }

        View homeBtn = findViewById(R.id.action_home);
        if (homeBtn != null) {
            homeBtn.setOnClickListener(v -> {
                if (actionCallback != null) actionCallback.onAction(ACTION_HOME);
            });
        }

        View menuBtn = findViewById(R.id.action_menu);
        if (menuBtn != null) {
            menuBtn.setOnClickListener(v -> {
                if (actionCallback != null) actionCallback.onAction(ACTION_MENU);
            });
        }

        // SurfaceView callbacks and touch forwarding
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surface = holder.getSurface();
                Log.d(TAG, "surfaceCreated: " + surface);
                if (surfaceReadyCallback != null) {
                    surfaceReadyCallback.onSurfaceReady(surface);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surface = holder.getSurface();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surface = null;
                if (surfaceReadyCallback != null) {
                    surfaceReadyCallback.onSurfaceDestroyed();
                }
            }
        });

        surfaceView.setOnTouchListener((view, motionEvent) -> {
            if (onDisplayTouchListener != null) {
                return onDisplayTouchListener.onTouch(view, motionEvent);
            }
            return false;
        });
    }

    public void setCloseListener(OnClickListener closeListener) {
        this.closeListener = closeListener;
    }

    public void setMoveCallback(OnMoveCallback moveCallback) {
        this.moveCallback = moveCallback;
    }

    public void setActionCallback(OnActionCallback actionCallback) {
        this.actionCallback = actionCallback;
    }

    public void setOnDisplayTouchListener(OnTouchListener onDisplayTouchListener) {
        this.onDisplayTouchListener = onDisplayTouchListener;
    }

    public void setSurfaceReadyCallback(SurfaceReadyCallback callback) {
        this.surfaceReadyCallback = callback;
        if (surface != null && surface.isValid() && callback != null) {
            callback.onSurfaceReady(surface);
        }
    }

    public SurfaceView getSurfaceView() {
        return surfaceView;
    }

    public void setRemote(int w, int h) {
        if (w <= 0 || h <= 0) return;
        this.remoteW = w;
        this.remoteH = h;

        post(() -> {
            int screenW = getScreenWidth();
            int targetW;
            if (w < h) {
                // Portrait remote: ~48% of screen width
                targetW = (int) (screenW * 0.48f);
            } else {
                // Landscape remote: ~65% of screen width
                targetW = (int) (screenW * 0.65f);
            }
            applyConstrainedSize(targetW);
        });
    }

    public void applyConstrainedSize(int targetWidth) {
        int screenW = getScreenWidth();
        int screenH = getScreenHeight();
        float aspect = (remoteW > 0 && remoteH > 0) ? ((float) remoteW / (float) remoteH) : (9f / 16f);

        int minW = dpToPx(160);
        int maxW = (int) (screenW * 0.95f);
        int clampedW = Math.max(minW, Math.min(maxW, targetWidth));
        int clampedH = (int) (clampedW / aspect);

        int maxH = (int) (screenH * 0.85f);
        if (clampedH > maxH) {
            clampedH = maxH;
            clampedW = (int) (clampedH * aspect);
        }

        ViewGroup.LayoutParams lp = container.getLayoutParams();
        lp.width = clampedW;
        lp.height = clampedH;
        container.setLayoutParams(lp);

        ViewGroup.LayoutParams hlp = header.getLayoutParams();
        hlp.width = clampedW;
        header.setLayoutParams(hlp);

        ViewGroup.LayoutParams alp = actionbar.getLayoutParams();
        alp.width = clampedW;
        actionbar.setLayoutParams(alp);

        requestLayout();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    private int getScreenWidth() {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return 1080;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels;
    }

    private int getScreenHeight() {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return 1920;
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    public void hideHintTip() {
        if (hintLayout != null) {
            hintLayout.setVisibility(GONE);
        }
    }

    public void showHintTip() {
        if (hintLayout != null) {
            hintLayout.setVisibility(VISIBLE);
        }
    }

    public Surface getDisplaySurface() {
        return surface != null ? surface : surfaceView.getHolder().getSurface();
    }

    public int getSurfaceWidth() {
        return surfaceView != null && surfaceView.getWidth() > 0 ? surfaceView.getWidth() : container.getWidth();
    }

    public int getSurfaceHeight() {
        return surfaceView != null && surfaceView.getHeight() > 0 ? surfaceView.getHeight() : container.getHeight();
    }

    public interface OnMoveCallback {
        void onMove(float x, float y);
    }

    public interface OnActionCallback {
        void onAction(int actionType);
    }

    public interface SurfaceReadyCallback {
        void onSurfaceReady(Surface surface);
        void onSurfaceDestroyed();
    }
}
