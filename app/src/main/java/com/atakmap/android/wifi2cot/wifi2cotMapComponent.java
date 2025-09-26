
package com.atakmap.android.wifi2cot;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;

import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.dropdown.DropDownMapComponent;

import com.atakmap.coremap.log.Log;
import com.atakmap.android.wifi2cot.plugin.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class wifi2cotMapComponent extends DropDownMapComponent {

    private static final String TAG = "wifi2cotMapComponent";

    private Context pluginContext;

    private MapView mapView;

    private wifi2cotDropDownReceiver ddr;

    private WifiManager wifiManager;
    private BluetoothLeScanner bluetoothLeScanner;

    private BroadcastReceiver wifiScanReceiver;
    private ScanCallback bleScanCallback;
    private boolean bleScanActive = false;

    // nodes will hold k,v for the BSSID and the rssi,lat,lng,bssid,ssid values
    private final static HashMap<String, List<String[]>> wifiNodes = new HashMap<>();
    private final static HashMap<String, List<String[]>> bleNodes = new HashMap<>();

    public void onCreate(final Context context, Intent intent,
            final MapView view) {

        context.setTheme(R.style.ATAKPluginTheme);
        super.onCreate(context, intent, view);
        pluginContext = context;
        mapView = view;

        ddr = new wifi2cotDropDownReceiver(
                view, context, this);

        Log.d(TAG, "registering the plugin filter");
        DocumentedIntentFilter ddFilter = new DocumentedIntentFilter();
        ddFilter.addAction(wifi2cotDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(ddr, ddFilter);

        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

        wifiScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                boolean success = intent.getBooleanExtra(
                        WifiManager.EXTRA_RESULTS_UPDATED, false);
                Log.d(TAG, "wifiScanReceiver: " + success);
                if (success) {
                    scanSuccess();
                } else {
                    // scan failure handling
                    scanFailure();
                }
            }
        };

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        context.registerReceiver(wifiScanReceiver, intentFilter);

        boolean success = wifiManager.startScan();
        if (!success) {
            // scan failure handling
            scanFailure();
        }

        BluetoothManager bluetoothManager = (BluetoothManager) context
                .getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            BluetoothAdapter adapter = bluetoothManager.getAdapter();
            if (adapter != null) {
                bluetoothLeScanner = adapter.getBluetoothLeScanner();
            }
        }
        bleScanCallback = buildBleScanCallback();
    }

    double getDistance(int rssi, int txPower, int freq) {
        /*
         * RSSI = TxPower - 10 * n * lg(d)
         * n = 2 (in free space)
         *
         * d = 10 ^ ((TxPower - RSSI) / (10 * n))
         */
        int n = 2;
        if (freq > 5000) {
            n++;
        }
        return Math.pow(10d, ((double) txPower - rssi) / (10 * n));
    }

    private void scanSuccess() {

        Log.d(TAG, "Inside scanSuccess");

        if (!ddr.isScanning()) {
            Log.d(TAG, "Not in scanning mode");
            return;
        }

        new Thread() {
            @Override
            public void run() {

                List<android.net.wifi.ScanResult> results = wifiManager
                        .getScanResults();
                for (android.net.wifi.ScanResult s : results) {

                    Log.d(TAG, "Scan result: BSSID: " + s.BSSID + " SSID: "
                            + s.SSID + " RSSI: " + s.level + " Freq: "
                            + s.frequency);

                    double lat = mapView.getSelfMarker().getPoint()
                            .getLatitude();
                    double lng = mapView.getSelfMarker().getPoint()
                            .getLongitude();

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
        // handle failure: new scan did NOT succeed
        // consider using old scan results: these are the OLD results!
        List<android.net.wifi.ScanResult> results = wifiManager.getScanResults();
        Log.d(TAG, "Scan failed");
//  ... potentially use older scan results ...
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

    public void startBleScan() {
        if (bluetoothLeScanner == null || bleScanCallback == null) {
            Log.w(TAG, "BLE scanner unavailable");
            return;
        }
        if (bleScanActive) {
            return;
        }
        try {
            bluetoothLeScanner.startScan(bleScanCallback);
            bleScanActive = true;
            Log.d(TAG, "BLE scan started");
        } catch (SecurityException e) {
            Log.e(TAG, "Missing permission to start BLE scan", e);
        }
    }

    public void stopBleScan() {
        if (bluetoothLeScanner == null || bleScanCallback == null
                || !bleScanActive) {
            return;
        }
        try {
            bluetoothLeScanner.stopScan(bleScanCallback);
            bleScanActive = false;
            Log.d(TAG, "BLE scan stopped");
        } catch (SecurityException e) {
            Log.e(TAG, "Missing permission to stop BLE scan", e);
        }
    }

    private ScanCallback buildBleScanCallback() {
        return new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                handleBleScanResult(result);
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) {
                    handleBleScanResult(result);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "BLE scan failed with error code " + errorCode);
            }
        };
    }

    private void handleBleScanResult(ScanResult result) {
        if (!ddr.isScanning()) {
            return;
        }
        if (result == null) {
            return;
        }

        double lat = mapView.getSelfMarker().getPoint().getLatitude();
        double lng = mapView.getSelfMarker().getPoint().getLongitude();

        if (lat == 0 && lng == 0) {
            Log.d(TAG, "Skipping BLE sample - no GPS fix");
            return;
        }

        BluetoothDevice device = result.getDevice();
        if (device == null) {
            return;
        }

        String address = device.getAddress();
        if (address == null || address.isEmpty()) {
            return;
        }

        String name = device.getName();
        if ((name == null || name.isEmpty())
                && result.getScanRecord() != null) {
            name = result.getScanRecord().getDeviceName();
        }

        recordSignalSample(bleNodes, address, name,
                100 - Math.abs(result.getRssi()), lat, lng);
    }

    private void recordSignalSample(HashMap<String, List<String[]>> target,
            String key, String name, int strength, double lat, double lng) {
        if (key == null || key.isEmpty()) {
            return;
        }
        if ((lat == 0 && lng == 0) || Double.isNaN(lat)
                || Double.isNaN(lng)) {
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
