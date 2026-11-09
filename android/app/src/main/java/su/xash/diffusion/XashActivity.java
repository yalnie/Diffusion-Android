package su.xash.diffusion;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.content.SharedPreferences;
import android.provider.Settings.Secure;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

import su.xash.diffusion.util.CrashReports;
import su.xash.diffusion.util.SoftKeyboardPan;
import su.xash.diffusion.util.VoiceAudioCapture;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class XashActivity extends SDLActivity {
    public boolean prepareVoiceCapture(int sampleRate, int frames) {
        return getBooleanPreference("microphone", true)
                && checkCallingOrSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && VoiceAudioCapture.prepare(sampleRate, frames);
    }

    public void releaseVoiceCapture() {
        VoiceAudioCapture.releaseCapture();
    }

    private boolean mUseVolumeKeys;
    private volatile float[] mWindowInsets = new float[4];
    private String mPackageName;
    private static final String TAG = "XashActivity";
    private static final int MIN_SURFACE_WIDTH = 320;
    private static final int MIN_SURFACE_HEIGHT = 200;
    private SharedPreferences mPreferences;
    private String mCachedArgv;
    private int mFixedSurfaceWidth;
    private int mFixedSurfaceHeight;
    private boolean mStretchFixedSurface;
    private int mAppliedSurfaceWidth = -1;
    private int mAppliedSurfaceHeight = -1;
    private boolean mAppliedSurfaceStretch = false;

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        return new StartupSurface(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (mBrokenLibraries) {
            return;
        }

        ensurePreferences();

        try {
            if (su.xash.diffusion.discord.DiscordAuthManager.Companion.get(this).isAutoConnectEnabled() &&
                su.xash.diffusion.discord.DiscordAuthManager.Companion.get(this).hasToken()) {
                su.xash.diffusion.discord.DiscordRpcService.Companion.start(this);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to start Discord RPC service: " + t.getMessage());
        }

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && mSurface != null) {
            mSurface.setOnApplyWindowInsetsListener((view, insets) -> {
                updateWindowInsets(view, insets);
                return insets;
            });
            mSurface.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) ->
                updateWindowInsets(view, view.getRootWindowInsets()));
            mSurface.requestApplyInsets();
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            mFullscreenModeActive = true;
        });

        parseFixedResolution(getFinalArgv());
        applyFixedSurfaceSize();

        if (getBooleanPreference("keyboard_pans_screen", true)) {
            SoftKeyboardPan.assistActivity(this);
        } else {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
    }

    // JNI reads a snapshot; all View access stays on Android's UI thread.
    private float[] getWindowInsets() {
        return mWindowInsets;
    }

    private void updateWindowInsets(View surface, WindowInsets insets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }

        if (insets == null || surface.getWidth() <= 0 || surface.getHeight() <= 0) {
            return;
        }

        int left = 0, top = 0, right = 0, bottom = 0;
        DisplayCutout cutout = insets.getDisplayCutout();
        if (cutout != null) {
            left = cutout.getSafeInsetLeft();
            top = cutout.getSafeInsetTop();
            right = cutout.getSafeInsetRight();
            bottom = cutout.getSafeInsetBottom();
        }
        float width = surface.getWidth(), height = surface.getHeight();
        mWindowInsets = new float[] { left / width, top / height, right / width, bottom / height };
    }

    private AssetManager getAssets(boolean isEngine) {
        AssetManager am = null;

        if (isEngine) {
            am = getAssets();
        } else {
            try {
                am = getPackageManager().getResourcesForApplication(getCallingPackage()).getAssets();
            } catch (Exception e) {
                Log.e(TAG, "Unable to load mod assets!");
                e.printStackTrace();
            }
        }

        return am;
    }

    private String[] getAssetsList(boolean isEngine, String path) {
        AssetManager am = getAssets(isEngine);

        try {
            return am.list(path);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return new String[0];
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFixedSurfaceSize();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyFixedSurfaceSize();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (su.xash.diffusion.discord.DiscordRpcService.Companion.isRunning()) {
                su.xash.diffusion.discord.DiscordRpcService.Companion.stop(this);
            }
        } catch (Throwable ignored) {}
        System.exit(0);
    }

    @Override
    protected String[] getLibraries() {
        try {
            System.loadLibrary("c++_shared");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "c++_shared library not found or already loaded: " + e.getMessage());
        }
        return new String[]{"SDL2", "xash"};
    }

    @SuppressLint("HardwareIds")
    private String getAndroidID() {
        return Secure.getString(getContentResolver(), Secure.ANDROID_ID);
    }

    @SuppressLint("ApplySharedPref")
    private void saveAndroidID(String id) {
        getSharedPreferences("xash_preferences", MODE_PRIVATE).edit().putString("xash_id", id).commit();
    }

    private String loadAndroidID() {
        return getSharedPreferences("xash_preferences", MODE_PRIVATE).getString("xash_id", "");
    }

    @Override
    public String getCallingPackage() {
        if (mPackageName != null) {
            return mPackageName;
        }
        return super.getCallingPackage();
    }

    private static void appendStringExtra(StringBuilder sb, Intent intent, String key) {
        String value = intent.getStringExtra(key);
        if (value != null) {
            sb.append("  ").append(key).append(" = ").append(value).append('\n');
        }
    }

    private void recordLaunchInfo() {
        File pendingCrash = new File(getFilesDir(), "crashes/" + CrashReports.STACKTRACE_NAME);
        if (pendingCrash.exists() && pendingCrash.length() > 0) {
            return;
        }

        CrashReports.writeSystemInfo(this);

        Intent intent = getIntent();
        if (intent == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Action: ").append(intent.getAction()).append('\n');
        sb.append("Data: ").append(intent.getDataString()).append('\n');
        sb.append("Calling package: ").append(getCallingPackage()).append('\n');
        sb.append("Extras:\n");
        appendStringExtra(sb, intent, "gamedir");
        appendStringExtra(sb, intent, "gamelibdir");
        appendStringExtra(sb, intent, "pakfile");
        appendStringExtra(sb, intent, "basedir");
        appendStringExtra(sb, intent, "package");
        appendStringExtra(sb, intent, "argv");
        sb.append("  usevolume = ").append(intent.getBooleanExtra("usevolume", false)).append('\n');
        String[] env = intent.getStringArrayExtra("env");
        if (env != null) {
            sb.append("  env = ").append(Arrays.toString(env)).append('\n');
        }
        CrashReports.writeIntentInfo(this, sb.toString());
    }

    private void setupEnvironment() {
        File crashDir = new File(getFilesDir(), "crashes");
        crashDir.mkdirs();
        nativeSetenv("XASH3D_CRASH_DIR", crashDir.getAbsolutePath());

        recordLaunchInfo();

        ensurePreferences();

        mPackageName = getIntent().getStringExtra("package");
        if (mPackageName == null || mPackageName.isEmpty()) {
            mPackageName = getPackageName();
        }

        String basedir = getIntent().getStringExtra("basedir");
        if (basedir == null || basedir.isEmpty()) {
            basedir = getBaseDir();
        }
        File basedirFile = new File(basedir);
        if (!basedirFile.exists()) {
            basedirFile.mkdirs();
        }
        nativeSetenv("XASH3D_BASEDIR", basedir);

        String gamedir = getIntent().getStringExtra("gamedir");
        if (gamedir == null || gamedir.isEmpty()) {
            gamedir = "diffusion";
        }
        nativeSetenv("XASH3D_GAME", gamedir);

        File gameDirFile = new File(basedirFile, gamedir);
        if (!gameDirFile.exists()) {
            gameDirFile.mkdirs();
        }

        String gamelibdir = getIntent().getStringExtra("gamelibdir");
        if (gamelibdir == null || gamelibdir.isEmpty()) {
            gamelibdir = getApplicationInfo().nativeLibraryDir;
        }
        nativeSetenv("XASH3D_GAMELIBDIR", gamelibdir);

        String rodir = System.getenv("XASH3D_RODIR");
        if (rodir == null) {
            rodir = getFilesDir().getAbsolutePath() + "/gamelibs";
            nativeSetenv("XASH3D_RODIR", rodir);
        }
        Log.i(TAG, "XASH3D_RODIR = " + rodir);

        String pakfile = getIntent().getStringExtra("pakfile");
        if (pakfile != null) {
            nativeSetenv("XASH3D_EXTRAS_PAK2", pakfile);
        }

        String[] env = getIntent().getStringArrayExtra("env");
        if (env != null) {
            for (int i = 0; i < env.length; i += 2) {
                nativeSetenv(env[i], env[i + 1]);
            }
        }
    }

    @Override
    protected String[] getArguments() {
        setupEnvironment();
        return getFinalArgv().split(" ");
    }

    private String getFinalArgv() {
        if (mCachedArgv != null) {
            return mCachedArgv;
        }

        setupEnvironment();

        String argv = getIntent().getStringExtra("argv");
        String gamedir = getIntent().getStringExtra("gamedir");
        String basedir = getIntent().getStringExtra("basedir");
        mUseVolumeKeys = getIntent().getBooleanExtra("usevolume", false);

        if (gamedir == null || gamedir.isEmpty()) {
            gamedir = "diffusion";
        }

        if (argv == null) {
            argv = "";
        }

        if (basedir == null || basedir.isEmpty()) {
            basedir = getBaseDir();
        }

        File basedirFile = new File(basedir);
        if (!basedirFile.exists()) {
            basedirFile.mkdirs();
        }

        String xash_id = loadAndroidID();
        if (xash_id.isEmpty()) {
            xash_id = getAndroidID();
            saveAndroidID(xash_id);
        }

        argv = argv.trim();

        if (!argv.contains("-basedir")) {
            argv += " -basedir " + basedir;
        }

        if (!argv.contains("-xashid")) {
            argv += " -xashid " + xash_id;
        }

        String globalArgs = mPreferences.getString("global_arguments", "");
        if (globalArgs != null && !globalArgs.trim().isEmpty()) {
            argv = combineArguments(argv, globalArgs.trim());
        }

        if (!argv.contains("-game") && !gamedir.equals("valve")) {
            argv += " -game " + gamedir;
        }

        if (!hasArgument(argv, "-ref")) {
            argv += " -ref gl4es";
        }

        String resolutionArgs = getRenderResolutionArguments(argv);
        if (!resolutionArgs.isEmpty()) {
            argv += " " + resolutionArgs;
        }

        if (getBooleanPreference("stretch_resolution", false) && !hasArgument(argv, "-stretch_resolution")) {
            argv += " -stretch_resolution";
        }

        if (getBooleanPreference("fix_font", false) && !hasArgument(argv, "-fixfont")) {
            argv += " -fixfont";
        }

        if (!getBooleanPreference("keyboard_pans_screen", true) && !hasArgument(argv, "-noresize")) {
            argv += " -noresize";
        }

        Log.d(TAG, "Final argv: " + argv);
        mCachedArgv = argv.trim();
        return mCachedArgv;
    }

    private String getRenderResolutionArguments(String argv) {
        if (hasArgument(argv, "-width") || hasArgument(argv, "-height")) {
            return "";
        }

        String resolution = mPreferences.getString("render_resolution", "");
        if (resolution == null || resolution.trim().isEmpty()) {
            return getNativeResolutionArguments();
        }

        String[] parts = resolution.trim().split("[xX,\\s]+");
        if (parts.length < 2) {
            return "";
        }

        try {
            int width = Integer.parseInt(parts[0]);
            int height = Integer.parseInt(parts[1]);
            if (width < MIN_SURFACE_WIDTH || height < MIN_SURFACE_HEIGHT) {
                return "";
            }
            return "-width " + width + " -height " + height;
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private String getNativeResolutionArguments() {
        DisplayMetrics metrics = getRealDisplayMetrics();
        int width = Math.max(metrics.widthPixels, metrics.heightPixels);
        int height = Math.min(metrics.widthPixels, metrics.heightPixels);

        if (width <= 0 || height <= 0) {
            return "";
        }

        return "-width " + width + " -height " + height;
    }

    private DisplayMetrics getRealDisplayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return metrics;
    }

    private void parseFixedResolution(String argv) {
        mFixedSurfaceWidth = getIntArgument(argv, "-width", -1);
        mFixedSurfaceHeight = getIntArgument(argv, "-height", -1);
        mStretchFixedSurface = hasArgument(argv, "-stretch_resolution")
                || getBooleanPreference("stretch_resolution", false);
    }

    private void applyFixedSurfaceSize() {
        SurfaceView surface = findSurfaceView();
        if (surface == null) return;

        DisplayMetrics metrics = getRealDisplayMetrics();
        int displayWidth = Math.max(metrics.widthPixels, metrics.heightPixels);
        int displayHeight = Math.min(metrics.widthPixels, metrics.heightPixels);

        int renderWidth = (mFixedSurfaceWidth > 0) ? mFixedSurfaceWidth : displayWidth;
        int renderHeight = (mFixedSurfaceHeight > 0) ? mFixedSurfaceHeight : displayHeight;

        int layoutWidth = displayWidth;
        int layoutHeight = displayHeight;

        if (!mStretchFixedSurface && renderWidth > 0 && renderHeight > 0 && displayWidth > 0 && displayHeight > 0) {
            float renderAspect = (float) renderWidth / (float) renderHeight;
            float displayAspect = (float) displayWidth / (float) displayHeight;

            if (renderAspect > displayAspect) {
                layoutWidth = displayWidth;
                layoutHeight = Math.round(displayWidth / renderAspect);
            } else {
                layoutHeight = displayHeight;
                layoutWidth = Math.round(displayHeight * renderAspect);
            }
        }

        ViewGroup.LayoutParams params = surface.getLayoutParams();
        if (params != null && (params.width != layoutWidth || params.height != layoutHeight)) {
            params.width = layoutWidth;
            params.height = layoutHeight;
            surface.setLayoutParams(params);
        }

        if (mAppliedSurfaceWidth != renderWidth || mAppliedSurfaceHeight != renderHeight
                || mAppliedSurfaceStretch != mStretchFixedSurface) {
            surface.getHolder().setFixedSize(renderWidth, renderHeight);
            mAppliedSurfaceWidth = renderWidth;
            mAppliedSurfaceHeight = renderHeight;
            mAppliedSurfaceStretch = mStretchFixedSurface;
        }
    }

    private SurfaceView findSurfaceView() {
        View root = getWindow().getDecorView();
        return findSurfaceView(root);
    }

    private SurfaceView findSurfaceView(View view) {
        if (view instanceof SurfaceView) {
            return (SurfaceView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView found = findSurfaceView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private int getIntArgument(String argv, String key, int defaultValue) {
        if (argv == null) return defaultValue;
        String[] tokens = argv.split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            if (tokens[i].equals(key)) {
                try {
                    return Integer.parseInt(tokens[i + 1]);
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private boolean hasArgument(String argv, String key) {
        if (argv == null) return false;
        String[] tokens = argv.split("\\s+");
        for (String token : tokens) {
            if (token.equals(key)) return true;
        }
        return false;
    }

    private String combineArguments(String primary, String extra) {
        if (primary == null || primary.isEmpty()) return extra != null ? extra : "";
        if (extra == null || extra.isEmpty()) return primary;
        return primary + " " + extra;
    }

    private void ensurePreferences() {
        if (mPreferences == null) {
            mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        }
    }

    private boolean getBooleanPreference(String key, boolean defaultValue) {
        ensurePreferences();
        return mPreferences.getBoolean(key, defaultValue);
    }

    private String getBaseDir() {
        boolean useInternal = getBooleanPreference("storage_toggle", true);
        if (useInternal) {
            File internal = getExternalFilesDir(null);
            if (internal != null) {
                return internal.getAbsolutePath();
            }
        }
        return mPreferences.getString("game_path", Environment.getExternalStorageDirectory().getAbsolutePath() + "/xash");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mUseVolumeKeys) {
            return super.dispatchKeyEvent(event);
        }
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        return super.dispatchKeyEvent(event);
    }
}
