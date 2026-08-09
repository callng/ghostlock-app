package com.ghostlock.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final String TAG = "GhostLockApp";
    private static final String BINARY_NAME = "libghostlock.so";
    private static final String KSUD_NAME = "ksud";
    private static final String PREFS = "ghostlock_prefs";
    private static final String PREF_CPU_PAIR = "cpu_pair";
    /** KernelSU manager packages to source libksud.so from, in preference order. */
    private static final String[] KSUD_PACKAGES = {
            "top.owo233.kernelsu", // forked KernelSU manager (preferred)
            "me.weishu.kernelsu",  // official KernelSU manager
            "com.resukisu.resukisu", // ReSukiSU manager
    };
    private static final int COLOR_RED = 0xFFFF6B6B;
    private static final int COLOR_GREEN = 0xFF5FD68A;
    private static final int COLOR_YELLOW = 0xFFFFC94D;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final StringBuilder logBuffer = new StringBuilder();
    /** How much of logBuffer has already been rendered into logView. */
    private int logViewSynced;
    /** False while the window is fully invisible (onStop), e.g. minimized. */
    private volatile boolean uiVisible;
    private TextView deviceInfo;
    private TextView statusInfo;
    private TextView logView;
    private View statusDot;
    private View statusChip;
    private LinearLayout kernelChip;
    private TextView kernelChipText;
    private Spinner cpuSpinner;
    private ScrollView logScroll;
    private Button runButton;
    private Button copyButton;
    private View rootView;
    private final List<int[]> cpuPairs = new ArrayList<>();
    private final List<String> cpuPairLabels = new ArrayList<>();
    private int cpuPairIndex;

    /**
     * Mirrors the offset tables under src/kernels: only these uname builds are supported.
     */
    private static boolean isKernelSupported() {
        String version = System.getProperty("os.version", "");
        for (String supported : SupportedKernels.UNAMES) {
            if (supported.equals(version)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the value when it looks like a real device name, else null.
     */
    private static String validDeviceName(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.contains("unknown") || lower.contains("null")) {
            return null;
        }
        return v;
    }

    @SuppressLint("PrivateApi")
    private static String getSystemProperty(String key) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Object value = props.getMethod("get", String.class).invoke(null, key);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readSysFile(String path) {
        File f = new File(path);
        if (!f.isFile()) {
            return "";
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    private static List<Integer> parseCpuList(String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        for (String part : s.split(",")) {
            String[] range = part.split("-");
            try {
                int lo = Integer.parseInt(range[0].trim());
                int hi = range.length > 1 ? Integer.parseInt(range[1].trim()) : lo;
                for (int cpu = lo; cpu <= hi; cpu++) {
                    out.add(cpu);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static long readMaxFreq(int cpu) {
        String v = readSysFile("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/cpuinfo_max_freq");
        if (v.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String formatFreq(long khz) {
        if (khz >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.2f GHz", khz / 1_000_000.0);
        }
        return String.format(Locale.ROOT, "%.0f MHz", khz / 1000.0);
    }

    private void buildCpuPairs() {
        cpuPairs.clear();
        cpuPairLabels.clear();
        cpuPairs.add(new int[]{0, 1});
        long autoFreq = readMaxFreq(0);
        cpuPairLabels.add(getString(R.string.cpu_pair_auto) + (autoFreq > 0 ? " · " + formatFreq(autoFreq) : ""));

        List<Integer> online = parseCpuList(readSysFile("/sys/devices/system/cpu/online"));
        if (online.isEmpty()) {
            return;
        }
        Map<Long, List<Integer>> byFreq = new TreeMap<>(Collections.reverseOrder());
        for (int cpu : online) {
            long freq = readMaxFreq(cpu);
            if (freq > 0) {
                byFreq.computeIfAbsent(freq, k -> new ArrayList<>()).add(cpu);
            }
        }
        for (Map.Entry<Long, List<Integer>> entry : byFreq.entrySet()) {
            List<Integer> cluster = entry.getValue();
            Collections.sort(cluster);
            String freqText = " · " + formatFreq(entry.getKey());
            for (int i = 0; i + 1 < cluster.size(); i += 2) {
                int main = cluster.get(i);
                int consumer = cluster.get(i + 1);
                cpuPairs.add(new int[]{main, consumer});
                cpuPairLabels.add(main + "," + consumer + freqText);
            }
        }
    }

    private void restoreCpuPair() {
        cpuPairIndex = 0;
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_CPU_PAIR, "auto");
        if (saved == null || saved.equals("auto")) {
            return;
        }
        String[] parts = saved.split(",");
        if (parts.length != 2) {
            return;
        }
        try {
            int main = Integer.parseInt(parts[0].trim());
            int consumer = Integer.parseInt(parts[1].trim());
            for (int i = 0; i < cpuPairs.size(); i++) {
                int[] pair = cpuPairs.get(i);
                if (pair[0] == main && pair[1] == consumer) {
                    cpuPairIndex = i;
                    return;
                }
            }
        } catch (NumberFormatException ignored) {
        }
    }

    /**
     * Color a whole log line by its leading marker. The native binary only
     * colors the "[..]" prefix (message text stays default) and the script log
     * is plain text, so per-line coloring is what makes the log readable.
     */
    private static CharSequence colorize(String line) {
        int color = markerColor(line);
        if (color == -1) {
            return line;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(line);
        sb.setSpan(new ForegroundColorSpan(color), 0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static int markerColor(String line) {
        if (line.startsWith("[+]")) return COLOR_GREEN;
        if (line.startsWith("[-]") || line.startsWith("[!]")) return COLOR_RED;
        if (line.startsWith("[*]")) return COLOR_YELLOW;
        if (line.startsWith("error") || line.startsWith("Error")) return COLOR_RED;
        if (line.startsWith("warning")) return COLOR_YELLOW;
        return -1;
    }

    private static String stripAnsi(String input) {
        return input.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst, false)) {
            copyStream(in, out);
        }
    }

    private static void setViewColor(View view, int color) {
        if (view.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) view.getBackground().mutate()).setColor(color);
        } else {
            view.setBackgroundTintList(ColorStateList.valueOf(color));
        }
    }

    private static String firstValidProperty(String... keys) {
        for (String key : keys) {
            String value = validDeviceName(getSystemProperty(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupSystemBars();

        rootView = findViewById(R.id.root);
        deviceInfo = findViewById(R.id.deviceInfo);
        statusInfo = findViewById(R.id.statusInfo);
        statusDot = findViewById(R.id.statusDot);
        statusChip = findViewById(R.id.statusChip);
        logView = findViewById(R.id.logView);
        logScroll = findViewById(R.id.logScroll);
        runButton = findViewById(R.id.runButton);
        copyButton = findViewById(R.id.copyButton);
        kernelChip = findViewById(R.id.kernelChip);
        kernelChipText = findViewById(R.id.kernelChipText);
        cpuSpinner = findViewById(R.id.cpuSpinner);

        applyWindowInsetsPadding();
        deviceInfo.setText(buildDeviceSummary());
        buildCpuPairs();
        restoreCpuPair();
        applyKernelStatus();
        setRunState(RunState.IDLE, getString(R.string.status_idle));

        runButton.setOnClickListener(v -> startExploit());
        copyButton.setOnClickListener(v -> copyLogs());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item_right, cpuPairLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cpuSpinner.setAdapter(adapter);
        cpuSpinner.setSelection(cpuPairIndex);
        cpuSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                cpuPairIndex = position;
                int[] pair = cpuPairs.get(position);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(PREF_CPU_PAIR, position == 0 ? "auto" : pair[0] + "," + pair[1])
                        .apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        uiVisible = true;
        // Catch up on log lines written while the window was not visible.
        flushPendingLog();
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiVisible = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-arm keep-screen-on if the exploit is still running when we come back.
        keepScreenOn(running.get());
    }

    @Override
    protected void onPause() {
        super.onPause();
        keepScreenOn(false);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void setupSystemBars() {
        Window window = getWindow();
        int barColor = getColor(R.color.status_bar);

        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(barColor);
        window.setNavigationBarColor(getColor(R.color.nav_bar));

        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);

        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            int lightStatus = getResources().getBoolean(R.bool.window_light_status_bar) ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0;
            int lightNav = getResources().getBoolean(R.bool.window_light_navigation_bar) ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
            controller.setSystemBarsAppearance(lightStatus | lightNav, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    private void applyWindowInsetsPadding() {
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            top = bars.top;
            bottom = bars.bottom;
            int side = dp(20);
            v.setPadding(side, top + dp(12), side, bottom + dp(12));
            return insets;
        });
        rootView.requestApplyInsets();
    }

    private String buildDeviceSummary() {
        return getString(R.string.device_label) + ": " + resolveDeviceName() + "\n" + getString(R.string.kernel_label) + ": " + System.getProperty("os.version", "unknown");
    }

    private void applyKernelStatus() {
        boolean ok = isKernelSupported();
        int color = getColor(ok ? R.color.status_success : R.color.status_error);
        int bg = getColor(ok ? R.color.status_success_bg : R.color.status_error_bg);
        kernelChipText.setText(ok ? R.string.kernel_supported : R.string.kernel_unsupported);
        kernelChipText.setTextColor(color);
        setViewColor(kernelChip, bg);
    }

    /**
     * Sales/market name of the device, matching the language of the current region.
     */
    private String resolveDeviceName() {
        boolean cn = "CN".equalsIgnoreCase(Locale.getDefault().getCountry());
        String marketName = firstValidProperty(cn ? "ro.vendor.oplus.market.name" : "ro.vendor.oplus.market.enname", cn ? "ro.vendor.oplus.market.enname" : "ro.vendor.oplus.market.name", "ro.product.marketname");
        return marketName != null ? marketName : Build.MANUFACTURER + " " + Build.MODEL;
    }

    private void startExploit() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        setRunState(RunState.RUNNING, getString(R.string.status_running));
        keepScreenOn(true);
        appendLog("==== start ====");
        appendLog("cpu pair: " + cpuPairLabels.get(cpuPairIndex));
        worker.execute(() -> {
            int code = 1;
            try {
                File workDir = getFilesDir();
                File binary = resolveBinary();
                File ksud = prepareKsud(workDir);
                if (ksud != null) {
                    appendLog("ksud ready");
                } else {
                    appendLog("warning: ksud not found");
                }
                code = runBinary(binary, workDir);
                appendLog("exit code=" + code);
            } catch (Throwable t) {
                appendLog("error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                int finalCode = code;
                ui.post(() -> {
                    running.set(false);
                    // Task finished (exit code shown): screen may dim again.
                    keepScreenOn(false);
                    if (finalCode == 0) {
                        setRunState(RunState.SUCCESS, getString(R.string.status_success));
                    } else {
                        setRunState(RunState.FAILED, getString(R.string.status_failed) + " (" + finalCode + ")");
                    }
                });
            }
        });
    }

    private void setRunState(RunState state, String text) {
        statusInfo.setText(text);
        runButton.setEnabled(state != RunState.RUNNING);
        runButton.setText(state == RunState.RUNNING ? R.string.action_running : R.string.action_run);

        int color;
        int chipBg = switch (state) {
            case RUNNING -> {
                color = getColor(R.color.status_running);
                yield getColor(R.color.status_running_bg);
            }
            case SUCCESS -> {
                color = getColor(R.color.status_success);
                yield getColor(R.color.status_success_bg);
            }
            case FAILED -> {
                color = getColor(R.color.status_error);
                yield getColor(R.color.status_error_bg);
            }
            default -> {
                color = getColor(R.color.status_idle);
                yield getColor(R.color.status_idle_bg);
            }
        };

        statusInfo.setTextColor(color);
        if (statusDot.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) statusDot.getBackground().mutate()).setColor(color);
        } else {
            statusDot.setBackgroundTintList(ColorStateList.valueOf(color));
        }

        if (statusChip.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) statusChip.getBackground().mutate()).setColor(chipBg);
        } else {
            statusChip.setBackgroundTintList(ColorStateList.valueOf(chipBg));
        }
    }

    private File resolveBinary() throws IOException {
        File packaged = new File(getApplicationInfo().nativeLibraryDir, BINARY_NAME);
        if (packaged.isFile()) {
            appendLog("binary ready (" + packaged.length() + " bytes)");
            return packaged;
        }
        throw new IOException("missing native binary: " + packaged.getAbsolutePath());
    }

    private File prepareKsud(File workDir) {
        File out = new File(workDir, KSUD_NAME);
        List<String> candidates = new ArrayList<>();
        for (String pkg : KSUD_PACKAGES) {
            try {
                ApplicationInfo appInfo = getPackageManager().getApplicationInfo(pkg, 0);
                candidates.add(new File(appInfo.nativeLibraryDir, "libksud.so").getAbsolutePath());
            } catch (PackageManager.NameNotFoundException ignored) {
                appendLog("KernelSU manager not installed: " + pkg);
            }
        }
        candidates.add("/data/local/tmp/ksud");
        candidates.add("/data/adb/ksu/bin/ksud");

        for (String path : candidates) {
            File src = new File(path);
            if (!src.isFile()) {
                continue;
            }
            try {
                copyFile(src, out);
                try {
                    Os.chmod(out.getAbsolutePath(), 448);
                } catch (ErrnoException ignored) {
                }
                return out;
            } catch (Throwable t) {
                appendLog("copy ksud failed: " + t.getMessage());
            }
        }
        if (out.isFile()) {
            return out;
        }
        return null;
    }

    private int runBinary(File binary, File workDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(binary.getAbsolutePath());
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("GHOSTLOCK_HOME", workDir.getAbsolutePath());
        pb.environment().put("TMPDIR", workDir.getAbsolutePath());
        pb.environment().put("HOME", workDir.getAbsolutePath());
        if (cpuPairIndex != 0) {
            int[] pair = cpuPairs.get(cpuPairIndex);
            pb.environment().put("GHOSTLOCK_CORE", String.valueOf(pair[0]));
            pb.environment().put("GHOSTLOCK_CONSUMER_CORE", String.valueOf(pair[1]));
        }

        Process process = pb.start();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    appendLog(line);
                }
            } catch (IOException ignored) {
            }
        }, "ghostlock-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(300, TimeUnit.SECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
        // Drain buffered output, then force-unblock the reader: a late-load
        // daemon (zygisk) can inherit our pipe and keep it open forever.
        try {
            reader.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
        }
        try {
            reader.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        appendKsudLogTail(workDir);
        return finished ? process.exitValue() : -1;
    }

    /**
     * Show any late-load log lines (root-owned, chmod 644) the binary could
     * not stream to us before it was killed by the vendor root guard.
     */
    private void appendKsudLogTail(File workDir) {
        File logFile = new File(workDir, ".ghostlock_ksu.log");
        if (!logFile.isFile()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException ignored) {
            return;
        }
        String joined;
        synchronized (logBuffer) {
            joined = logBuffer.toString();
        }
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || joined.contains(t)) {
                continue;
            }
            appendLog(line);
        }
    }

    /**
     * Keep the screen on while the exploit is running so the task is never
     * interrupted by the display timing out. Only effective while this window
     * is visible, so no permission is needed.
     */
    private void keepScreenOn(boolean on) {
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void copyLogs() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) {
            return;
        }
        String text;
        synchronized (logBuffer) {
            text = logBuffer.toString();
        }
        cm.setPrimaryClip(ClipData.newPlainText("ghostlock-log", text));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void appendLog(String line) {
        if (line == null) {
            return;
        }
        final String msg = line.endsWith("\n") ? line : line + "\n";
        final String plain = stripAnsi(msg);
        final int bufferEnd;
        synchronized (logBuffer) {
            logBuffer.append(plain);
            bufferEnd = logBuffer.length();
        }
        android.util.Log.i(TAG, plain.trim());
        // Skip view updates while invisible; onStart flushes them in one pass.
        // Small windows (freeform) stay visible, so their log keeps scrolling.
        if (uiVisible) {
            ui.post(() -> appendToView(plain, bufferEnd));
        }
    }

    /** Main-thread render of one log line; advances the render cursor. */
    private void appendToView(String plain, int bufferEnd) {
        boolean skip;
        synchronized (logBuffer) {
            // A flushPendingLog on onStart may already have rendered this line
            // if the message was posted right before the window went away.
            skip = bufferEnd <= logViewSynced;
        }
        if (skip) {
            return;
        }
        logView.append(colorize(plain));
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        synchronized (logBuffer) {
            if (bufferEnd > logViewSynced) {
                logViewSynced = bufferEnd;
            }
        }
    }

    /**
     * Render everything written since the last sync in one pass, so
     * backgrounding never drops or duplicates log lines.
     */
    private void flushPendingLog() {
        String pending;
        synchronized (logBuffer) {
            if (logViewSynced >= logBuffer.length()) {
                return;
            }
            pending = logBuffer.substring(logViewSynced);
            logViewSynced = logBuffer.length();
        }
        for (String line : pending.split("\n")) {
            if (!line.isEmpty()) {
                logView.append(colorize(line + "\n"));
            }
        }
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private enum RunState {
        IDLE, RUNNING, SUCCESS, FAILED
    }
}
