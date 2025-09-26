package com.atakmap.android.wifi2cot;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Build;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.wifi2cot.plugin.R;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class wifi2cotMapComponent extends DropDownMapComponent {

    private static final String TAG = "wifi2cotMapComponent";

    private MapView mapView;
    private wifi2cotDropDownReceiver ddr;

    private WifiManager wifiManager;
    private BroadcastReceiver wifiScanReceiver;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private ScanCallback bleScanCallback;
    private boolean bleScanActive = false;

    private static final HashMap<String, List<String[]>> wifiNodes = new HashMap<>();
    private static final HashMap<String, List<String[]>> bleNodes = new HashMap<>();

    public void onCreate(final Context context, Intent intent, final MapView view) {

        context.setTheme(R.style.ATAKPluginTheme);
        super.onCreate(context, intent, view);
        mapView = view;

        ddr = new wifi2cotDropDownReceiver(view, context, this);

        Log.d(TAG, "registering the plugin filter");
        DocumentedIntentFilter ddFilter = new DocumentedIntentFilter();
        ddFilter.addAction(wifi2cotDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(ddr, ddFilter);

        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            BluetoothManager bluetoothManager =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager != null) {
                bluetoothAdapter = bluetoothManager.getAdapter();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                        && bluetoothAdapter != null) {
                    bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
                    bleScanCallback = createBleScanCallback();
                }
            }
        }

        wifiScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                boolean success = intent.getBooleanExtra(
                        WifiManager.EXTRA_RESULTS_UPDATED, false);
                Log.d(TAG, "wifiScanReceiver: " + success);
                if (success) {
                    scanSuccess();
                } else {
                    scanFailure();
                }
            }
        };

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        context.registerReceiver(wifiScanReceiver, intentFilter);

        boolean success = wifiManager != null && wifiManager.startScan();
        if (!success) {
            scanFailure();
        }
    }

    double getDistance(int rssi, int txPower, int freq) {
        int n = 2;
        if (freq > 5000) {
            n++;
        }
        return Math.pow(10d, ((double) txPower - rssi) / (10 * n));
    }

    private void scanSuccess() {

        Log.d(TAG, "Inside scanSuccess");

        if (ddr == null || !ddr.isScanning()) {
            Log.d(TAG, "Not in scanning mode");
            return;
        }

        new Thread() {
            @Override
            public void run() {

                if (wifiManager == null) {
                    return;
                }

                List<android.net.wifi.ScanResult> results = wifiManager.getScanResults();
                for (android.net.wifi.ScanResult s : results) {

                    Log.d(TAG, "Scan result: BSSID: " + s.BSSID + " SSID: " + s.SSID
                            + " RSSI: " + s.level + " Freq: " + s.frequency);

                    if (mapView == null || mapView.getSelfMarker() == null
                            || mapView.getSelfMarker().getPoint() == null) {
                        Log.d(TAG, "MapView not ready for Wi-Fi sample");
                        return;
                    }

                    double lat = mapView.getSelfMarker().getPoint().getLatitude();
                    double lng = mapView.getSelfMarker().getPoint().getLongitude();

                    if (lat == 0 && lng == 0) {
                        Log.d(TAG, "No GPS fix");
                        return;
                    }

                    recordSignalSample(wifiNodes, s.BSSID, s.SSID,
                            100 - Math.abs(s.level), lat, lng);
                }
            }
        }.start();
    }

    private void scanFailure() {
        if (wifiManager == null) {
            return;
        }
        List<android.net.wifi.ScanResult> results = wifiManager.getScanResults();
        Log.d(TAG, "Scan failed");
        for (android.net.wifi.ScanResult s : results) {
            Log.d(TAG, String.format("Previous result: %s (%s)", s.SSID, s.BSSID));
        }
    }

    private ScanCallback createBleScanCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return null;
        }

        return new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                handleBleScanResult(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                if (results == null) {
                    return;
                }
                for (ScanResult result : results) {
                    handleBleScanResult(result);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "BLE scan failed: " + errorCode);
            }
        };
    }

    private void handleBleScanResult(ScanResult result) {
        if (result == null || ddr == null || !ddr.isBleScanning()) {
            return;
        }

        if (mapView == null || mapView.getSelfMarker() == null
                || mapView.getSelfMarker().getPoint() == null) {
            Log.d(TAG, "MapView not ready for BLE sample");
            return;
        }

        double lat = mapView.getSelfMarker().getPoint().getLatitude();
        double lng = mapView.getSelfMarker().getPoint().getLongitude();

        if (lat == 0.0 && lng == 0.0) {
            Log.d(TAG, "No GPS fix for BLE sample");
            return;
        }

        String latString = String.valueOf(lat);
        String lngString = String.valueOf(lng);

        if (latString.startsWith("0.0") && lngString.startsWith("0.0")) {
            return;
        }

        if (result.getDevice() == null || result.getDevice().getAddress() == null
                || result.getDevice().getAddress().isEmpty()) {
            return;
        }

        String address = result.getDevice().getAddress();
        String name = result.getDevice().getName();
        if (name == null) {
            name = "";
        }

        int quality = 100 - Math.abs(result.getRssi());
        if (quality < 0) {
            quality = 0;
        }

        recordSignalSample(bleNodes, address, name, quality, lat, lng);
    }

    @SuppressLint("MissingPermission")
    public boolean startBleScan() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.w(TAG, "BLE scanning not supported on this device");
            return false;
        }

        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth adapter not available");
            return false;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth adapter is disabled");
            return false;
        }

        if (bluetoothLeScanner == null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

        if (bluetoothLeScanner == null) {
            Log.w(TAG, "Bluetooth LE scanner not available");
            return false;
        }

        if (bleScanCallback == null) {
            bleScanCallback = createBleScanCallback();
        }

        if (bleScanCallback == null) {
            Log.w(TAG, "BLE scan callback not initialized");
            return false;
        }

        if (bleScanActive) {
            return true;
        }

        try {
            bluetoothLeScanner.startScan(bleScanCallback);
            bleScanActive = true;
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Missing permission to start BLE scan", e);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    public void stopBleScan() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        if (bluetoothLeScanner == null || bleScanCallback == null || !bleScanActive) {
            return;
        }

        try {
            bluetoothLeScanner.stopScan(bleScanCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "Missing permission to stop BLE scan", e);
        } finally {
            bleScanActive = false;
        }
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        if (wifiScanReceiver != null) {
            context.unregisterReceiver(wifiScanReceiver);
        }
        stopBleScan();
        super.onDestroyImpl(context, view);
    }

    public WifiManager getWifiManager() {
        return this.wifiManager;
    }

    public static HashMap<String, List<String[]>> getNodes() {
        return wifiNodes;
    }

    public static HashMap<String, List<String[]>> getBleNodes() {
        return bleNodes;
    }

    private void recordSignalSample(HashMap<String, List<String[]>> target,
            String key, String name, int strength, double lat, double lng) {
        if (key == null || key.isEmpty()) {
            return;
        }
        if ((lat == 0 && lng == 0) || Double.isNaN(lat) || Double.isNaN(lng)) {
            return;
        }

        String[] sample = new String[5];
        sample[0] = String.valueOf(strength);
        sample[1] = String.valueOf(lat);
        sample[2] = String.valueOf(lng);
        sample[3] = key;
        sample[4] = name != null ? name : "";

        synchronized (target) {
            List<String[]> data = target.get(key);
            if (data == null) {
                data = new ArrayList<>();
                target.put(key, data);
            }
            data.add(sample);
        }
    }
}

