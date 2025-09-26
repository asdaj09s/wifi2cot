
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

    private BroadcastReceiver wifiScanReceiver;

    private BluetoothAdapter bluetoothAdapter;

    private BluetoothLeScanner bluetoothLeScanner;

    private ScanCallback bleScanCallback;

    // nodes will hold k,v for the BSSID and the rssi,lat,lng,bssid,ssid values
    private final static HashMap<String, List<String[]>> nodes = new HashMap<>();

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

                List<android.net.wifi.ScanResult> results = wifiManager.getScanResults();
                for (android.net.wifi.ScanResult s : results) {

                    Log.d(TAG, "Scan result: BSSID: " + s.BSSID + " SSID: " + s.SSID + " RSSI: " + s.level + " Freq: " + s.frequency);

                    double lat = mapView.getSelfMarker().getPoint().getLatitude();
                    double lng = mapView.getSelfMarker().getPoint().getLongitude();

                    if (lat == 0 && lng == 0) {
                        Log.d(TAG, "No GPS fix");
                        return;
                    }

                    // data will be String["rssi", "self.lat", "self.lng", "bssid", "ssid"]
                    if (!nodes.containsKey(s.BSSID)) {
                        ArrayList<String[]> data = new ArrayList<>();
                        String[] sample = new String[5];
                        sample[0] = String.valueOf(100 - Math.abs(s.level));
                        sample[1] = String.valueOf(lat);
                        sample[2] = String.valueOf(lng);
                        sample[3] = s.BSSID;
                        sample[4] = s.SSID;

                        if (sample[1].startsWith("0.0") && sample[2].startsWith("0.0")) {
                            continue;
                        }

                        data.add(sample);
                        nodes.put(s.BSSID, data);
                    } else {
                        List<String[]> data = nodes.get(s.BSSID);
                        String[] sample = new String[5];
                        sample[0] = String.valueOf(100 - Math.abs(s.level));
                        sample[1] = String.valueOf(lat);
                        sample[2] = String.valueOf(lng);
                        sample[3] = s.BSSID;
                        sample[4] = s.SSID;

                        if (sample[1].startsWith("0.0") && sample[2].startsWith("0.0")) {
                            continue;
                        }

                        try {
                            data.add(sample);
                            nodes.put(s.BSSID, data);
                        } catch (NullPointerException e) {
                            e.printStackTrace();
                        }
                    }
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

        String[] sample = new String[5];
        sample[0] = String.valueOf(quality);
        sample[1] = latString;
        sample[2] = lngString;
        sample[3] = address;
        sample[4] = name;

        synchronized (bleNodes) {
            List<String[]> data = bleNodes.get(address);
            if (data == null) {
                data = new ArrayList<>();
                bleNodes.put(address, data);
            }
            data.add(sample);
        }
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

        try {
            bluetoothLeScanner.startScan(bleScanCallback);
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

        if (bluetoothLeScanner == null || bleScanCallback == null) {
            return;
        }

        try {
            bluetoothLeScanner.stopScan(bleScanCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "Missing permission to stop BLE scan", e);
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
        return nodes;
    }

    public static HashMap<String, List<String[]>> getBleNodes() {
        return bleNodes;
    }

}
