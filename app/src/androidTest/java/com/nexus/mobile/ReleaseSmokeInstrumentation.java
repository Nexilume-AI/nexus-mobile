package com.nexus.mobile;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs in the real, minified release process on a disposable emulator only.
 * am instrument -w -e case native|timeout com.nexus.mobile.test/.ReleaseSmokeInstrumentation
 * No imports of target classes: the test must not keep R8-stripped classes alive.
 */
public final class ReleaseSmokeInstrumentation extends Instrumentation {
    private String scenario;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        scenario = args.getString("case", "native");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!android.os.Build.FINGERPRINT.contains("generic") &&
                    !android.os.Build.MODEL.contains("sdk")) {
                throw new AssertionError("Disposable emulator required");
            }
            if (scenario.equals("native")) checkNativeInit();
            else if (scenario.equals("timeout")) checkTimeout();
            else throw new AssertionError("Unknown smoke scenario");
            result.putString("stream", "PASS release " + scenario + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // Do not render exception messages: only controlled test labels.
            result.putString("stream", "FAIL release " + scenario + ": " + failure.getClass().getSimpleName() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void checkNativeInit() throws Exception {
        ClassLoader loader = getTargetContext().getClassLoader();
        Class<?> factory = loader.loadClass("org.webrtc.PeerConnectionFactory");
        Class<?> options = loader.loadClass("org.webrtc.PeerConnectionFactory$InitializationOptions");
        Object builder = options.getMethod("builder", Context.class).invoke(null, getTargetContext());
        Object initialization = builder.getClass().getMethod("createInitializationOptions").invoke(builder);
        // The released beta aborted the process in this call (JNI_OnLoad).
        factory.getMethod("initialize", options).invoke(null, initialization);
        Object factoryBuilder = factory.getMethod("builder").invoke(null);
        Object instance = factoryBuilder.getClass().getMethod("createPeerConnectionFactory").invoke(factoryBuilder);
        factory.getMethod("dispose").invoke(instance);
    }

    private Service activeService(Class<?> serviceClass) throws Exception {
        for (Field field : serviceClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType().equals(serviceClass)) {
                field.setAccessible(true);
                return (Service) field.get(null);
            }
        }
        throw new AssertionError("Active service not found");
    }

    private void checkTimeout() throws Exception {
        Context context = getTargetContext();
        Class<?> serviceClass = context.getClassLoader().loadClass("com.nexus.mobile.NexusMobileService");
        // Require our override, not an inherited no-op on newer Android.
        java.lang.reflect.Method timeout = serviceClass.getDeclaredMethod("onTimeout", int.class, int.class);
        SharedPreferences runtime = context.getSharedPreferences("nexus_mobile_runtime", 0);
        SharedPreferences pairing = context.getSharedPreferences("nexus_mobile_secure", 0);
        SharedPreferences legacy = context.getSharedPreferences("nexus_mobile", 0);
        require(pairing.getAll().isEmpty() && legacy.getAll().isEmpty(), "Fresh emulator required");
        Activity activity = startActivitySync(new Intent().setClassName(context, "com.nexus.mobile.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Intent serviceIntent = new Intent().setClassName(context, serviceClass.getName());
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch accepted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread delayed = new Thread(() -> {
                try (Socket connection = server.accept()) {
                    accepted.countDown();
                    // Hold a TLS handshake across the timeout; the later network
                    // error must not restore Offline/Online or restart work.
                    release.await(8, TimeUnit.SECONDS);
                } catch (Exception ignored) { }
            });
            delayed.setDaemon(true);
            delayed.start();
            legacy.edit().putString("base_url", "https://127.0.0.1:" + server.getLocalPort())
                    .putString("device_id", "release-smoke-device")
                    .putString("token", "local-test-not-a-cloud-credential").commit();
            context.startForegroundService(serviceIntent);
            require(accepted.await(8, TimeUnit.SECONDS), "Sync request did not start");
            Service service = activeService(serviceClass);
            require(service != null, "Service not running");
            java.util.Map<String, ?> storedPairing = pairing.getAll();
            require(!storedPairing.isEmpty(), "Pairing not encrypted");
            long started = SystemClock.elapsedRealtime();
            runOnMainSync(() -> {
                try { timeout.invoke(service, 1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            long deadline = started + 2500;
            while (activeService(serviceClass) != null && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(20);
            }
            require(activeService(serviceClass) == null, "Timeout did not promptly stop service");
            require("stopped".equals(runtime.getString("state", "")), "Timeout not paused");
            release.countDown();
            delayed.join(2000);
            SystemClock.sleep(600);
            require("stopped".equals(runtime.getString("state", "")), "Late response overwrote paused state");
            require(pairing.getAll().equals(storedPairing), "Timeout changed pairing");
            // Explicit foreground Start may resume; no automatic restart loop.
            context.startForegroundService(serviceIntent);
            SystemClock.sleep(600);
            require(activeService(serviceClass) != null, "Explicit resume failed");
        } finally {
            context.stopService(serviceIntent);
            runOnMainSync(activity::finish);
            pairing.edit().clear().commit();
            legacy.edit().clear().commit();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
