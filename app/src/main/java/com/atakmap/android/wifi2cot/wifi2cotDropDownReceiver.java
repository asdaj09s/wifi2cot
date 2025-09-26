
package com.atakmap.android.wifi2cot;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;


import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.wifi2cot.plugin.R;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;

import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;


public class wifi2cotDropDownReceiver extends DropDownReceiver implements
        OnStateListener {

    public static final String TAG = "wifi2cotDropDownReceiver";

    public static final String SHOW_PLUGIN = "com.atakmap.android.wifi2cot.SHOW_PLUGIN";
    private final View templateView;
    private final Context pluginContext;

    private final wifi2cotMapComponent mc;

    private final Button start, stop, guess, startBle, stopBle, guessBle;
    private final ListView scanList;
    private final TextView trackingStatus;
    private final TextView scanEmptyView;

    private Timer scanTimer;
    private Timer uiUpdateTimer;
    private Timer trackTimer;

    private static final int MIN_SAMPLE_SIZE_FOR_DISPATCH = 3;
    private static final int MAX_TRACKING_MISSES = 3;

    private boolean scanning = false;
    private boolean bleScanning = false;
    private final ArrayAdapter<String> scanListAdapter;
    private final List<SignalSourceSummary> currentSummaries = new ArrayList<>();
    private String trackedId;
    private SignalSourceType trackedType;
    private String trackedDisplayName;
    private int missingTrackedCycles;

    /**************************** CONSTRUCTOR *****************************/

    public wifi2cotDropDownReceiver(final MapView mapView,
            final Context context, final wifi2cotMapComponent mc) {
        super(mapView);
        this.pluginContext = context;
        this.mc = mc;

        // Remember to use the PluginLayoutInflator if you are actually inflating a custom view
        // In this case, using it is not necessary - but I am putting it here to remind
        // developers to look at this Inflator
        templateView = PluginLayoutInflater.inflate(context,
                R.layout.main_layout, null);

        start = templateView.findViewById(R.id.start);
        stop = templateView.findViewById(R.id.stop);
        guess = templateView.findViewById(R.id.guess);
        startBle = templateView.findViewById(R.id.start_ble);
        stopBle = templateView.findViewById(R.id.stop_ble);
        guessBle = templateView.findViewById(R.id.guess_ble);

        scanList = templateView.findViewById(R.id.scan_list);
        trackingStatus = templateView.findViewById(R.id.tracking_status);
        scanEmptyView = templateView.findViewById(R.id.scan_empty);

        scanListAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_list_item_activated_1,
                new ArrayList<>());
        scanList.setAdapter(scanListAdapter);
        if (scanEmptyView != null) {
            scanList.setEmptyView(scanEmptyView);
        }
        scanList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        scanList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position,
                    long id) {
                SignalSourceSummary summary;
                synchronized (currentSummaries) {
                    if (position < 0 || position >= currentSummaries.size()) {
                        return;
                    }
                    summary = currentSummaries.get(position);
                }

                if (summary == null) {
                    return;
                }

                if (trackedId != null && trackedType != null
                        && summary.id.equals(trackedId)
                        && summary.type == trackedType) {
                    stopTracking();
                } else {
                    startTracking(summary, position);
                }
            }
        });
    }

    /**************************** PUBLIC METHODS *****************************/

    public void disposeImpl() {
        cancelScanTimer();
        cancelUiTimer();
        mc.stopBleScan();
        stopTracking();
        scanning = false;
        bleScanning = false;
    }

    /**************************** INHERITED METHODS *****************************/

    @Override
    public void onReceive(Context context, Intent intent) {

        final String action = intent.getAction();
        if (action == null)
            return;

        if (action.equals(SHOW_PLUGIN)) {

            Log.d(TAG, "showing plugin drop down");
            showDropDown(templateView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH,
                    HALF_HEIGHT, false, this);

            refreshScanSummaries();

            start.setOnClickListener(view -> {
                Log.d(TAG, "Starting scan");
                scanning = true;
                Toast.makeText(MapView._mapView.getContext(), "Starting scan",
                        Toast.LENGTH_LONG).show();
                synchronized (wifi2cotMapComponent.getNodes()) {
                    wifi2cotMapComponent.getNodes().clear();
                }
                synchronized (wifi2cotMapComponent.getBleNodes()) {
                    wifi2cotMapComponent.getBleNodes().clear();
                }
                resetScanList();
                cancelScanTimer();
                cancelUiTimer();
                bleScanning = mc.startBleScan();
                scanTimer = new Timer();
                scanTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (!mc.getWifiManager().startScan()) {
                            Log.d(TAG, "Scan failed");
                        }
                    }
                }, 0, 5000);
                uiUpdateTimer = new Timer();
                uiUpdateTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        refreshScanSummaries();
                    }
                }, 0, 3000);
            });

            stop.setOnClickListener(view -> {
                scanning = false;
                cancelScanTimer();
                cancelUiTimer();
                mc.stopBleScan();
                bleScanning = false;
                stopTracking();
                Log.d(TAG, "Stopping scan");
                Toast.makeText(MapView._mapView.getContext(), "Stopping scan",
                        Toast.LENGTH_LONG).show();
            });

            guess.setOnClickListener(view -> {
                Toast.makeText(MapView._mapView.getContext(), "Computing results",
                        Toast.LENGTH_LONG).show();
                compute();
            });

            startBle.setOnClickListener(view -> {
                Log.d(TAG, "Starting BLE scan");
                wifi2cotMapComponent.getBleNodes().clear();
                if (mc.startBleScan()) {
                    bleScanning = true;
                    Toast.makeText(MapView._mapView.getContext(), "Starting BLE scan",
                            Toast.LENGTH_LONG).show();
                } else {
                    bleScanning = false;
                    Toast.makeText(MapView._mapView.getContext(),
                            "Unable to start BLE scan", Toast.LENGTH_LONG).show();
                }
            });

            stopBle.setOnClickListener(view -> {
                Log.d(TAG, "Stopping BLE scan");
                mc.stopBleScan();
                bleScanning = false;
                Toast.makeText(MapView._mapView.getContext(), "Stopping BLE scan",
                        Toast.LENGTH_LONG).show();
            });

            guessBle.setOnClickListener(view -> {
                Toast.makeText(MapView._mapView.getContext(),
                        "Dispatching BLE results", Toast.LENGTH_LONG).show();
                computeBle();
            });
        }
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
        cancelScanTimer();
        cancelUiTimer();
        mc.stopBleScan();
        stopTracking();
        scanning = false;
        bleScanning = false;
    }

    private void cancelScanTimer() {
        if (scanTimer != null) {
            scanTimer.cancel();
            scanTimer = null;
        }
        mc.stopBleScan();
        bleScanning = false;
    }

    private void cancelUiTimer() {
        if (uiUpdateTimer != null) {
            uiUpdateTimer.cancel();
            uiUpdateTimer = null;
        }
    }

    private void stopTracking() {
        trackedId = null;
        trackedType = null;
        trackedDisplayName = null;
        missingTrackedCycles = 0;
        if (trackTimer != null) {
            trackTimer.cancel();
            trackTimer = null;
        }
        templateView.post(() -> {
            trackingStatus
                    .setText(pluginContext.getString(R.string.tracking_none));
            scanList.clearChoices();
        });
    }

    private void startTracking(SignalSourceSummary summary, int position) {
        trackedId = summary.id;
        trackedType = summary.type;
        trackedDisplayName = getDisplayName(summary);
        missingTrackedCycles = 0;
        if (trackTimer != null) {
            trackTimer.cancel();
        }
        trackTimer = new Timer();
        trackTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                dispatchTrackedSignalSource();
            }
        }, 0, 5000);

        templateView.post(() -> {
            scanList.setItemChecked(position, true);
            updateTrackingStatus(summary,
                    summary.sampleSize < MIN_SAMPLE_SIZE_FOR_DISPATCH);
            String message = pluginContext.getString(
                    R.string.tracking_template_with_type,
                    getTypeLabel(summary.type), getDisplayName(summary),
                    summary.id);
            Toast.makeText(MapView._mapView.getContext(), message,
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void refreshScanSummaries() {
        List<SignalSourceSummary> summaries = new ArrayList<>();
        HashMap<String, List<String[]>> wifiSnapshot = wifi2cotMapComponent
                .getNodes();
        synchronized (wifiSnapshot) {
            summaries.addAll(buildSignalSourceSummaries(wifiSnapshot,
                    SignalSourceType.WIFI));
        }
        HashMap<String, List<String[]>> bleSnapshot = wifi2cotMapComponent
                .getBleNodes();
        synchronized (bleSnapshot) {
            summaries.addAll(buildSignalSourceSummaries(bleSnapshot,
                    SignalSourceType.BLE));
        }

        summaries.sort((left, right) -> {
            int typeCompare = left.type.compareTo(right.type);
            if (typeCompare != 0) {
                return typeCompare;
            }
            String leftName = getDisplayName(left);
            String rightName = getDisplayName(right);
            int nameCompare = leftName.compareToIgnoreCase(rightName);
            if (nameCompare != 0) {
                return nameCompare;
            }
            return left.id.compareToIgnoreCase(right.id);
        });

        final List<String> formatted = new ArrayList<>();
        for (SignalSourceSummary summary : summaries) {
            formatted.add(formatSummary(summary));
        }

        synchronized (currentSummaries) {
            currentSummaries.clear();
            currentSummaries.addAll(summaries);
        }

        templateView.post(() -> {
            scanListAdapter.clear();
            for (String entry : formatted) {
                scanListAdapter.add(entry);
            }
            scanListAdapter.notifyDataSetChanged();

            if (trackedId != null && trackedType != null) {
                int index = findSummaryIndex(trackedId, trackedType);
                if (index >= 0) {
                    scanList.setItemChecked(index, true);
                    scanList.smoothScrollToPosition(index);
                } else {
                    scanList.clearChoices();
                    updateTrackingStatus(null, true);
                }
            }
        });
    }

    private void resetScanList() {
        stopTracking();
        synchronized (currentSummaries) {
            currentSummaries.clear();
        }
        templateView.post(() -> {
            scanListAdapter.clear();
            scanListAdapter.notifyDataSetChanged();
        });
    }

    private void dispatchTrackedSignalSource() {
        SignalSourceSummary summary = getSummaryForTrackedSource();
        if (summary == null) {
            if (trackedId != null && trackedType != null) {
                missingTrackedCycles++;
                if (missingTrackedCycles >= MAX_TRACKING_MISSES) {
                    final String lostName = trackedDisplayName != null
                            && !trackedDisplayName.isEmpty() ? trackedDisplayName
                                    : trackedId;
                    final SignalSourceType lostType = trackedType;
                    templateView.post(() -> Toast
                            .makeText(MapView._mapView.getContext(),
                                    pluginContext.getString(
                                            R.string.tracking_lost,
                                            getTypeLabel(lostType), lostName),
                                    Toast.LENGTH_SHORT)
                            .show());
                    stopTracking();
                } else {
                    updateTrackingStatus(null, true);
                }
            } else {
                updateTrackingStatus(null, false);
            }
            return;
        }

        missingTrackedCycles = 0;
        trackedDisplayName = getDisplayName(summary);
        if (summary.sampleSize < MIN_SAMPLE_SIZE_FOR_DISPATCH) {
            updateTrackingStatus(summary, true);
            return;
        }

        updateTrackingStatus(summary, false);
        dispatchSignalSourceSummary(summary);
    }

    private SignalSourceSummary getSummaryForTrackedSource() {
        if (trackedId == null || trackedType == null) {
            return null;
        }
        synchronized (currentSummaries) {
            for (SignalSourceSummary summary : currentSummaries) {
                if (summary.id.equals(trackedId)
                        && summary.type == trackedType) {
                    return summary;
                }
            }
        }
        return null;
    }

    private int findSummaryIndex(String id, SignalSourceType type) {
        synchronized (currentSummaries) {
            for (int i = 0; i < currentSummaries.size(); i++) {
                SignalSourceSummary summary = currentSummaries.get(i);
                if (summary.type == type && summary.id.equals(id)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void updateTrackingStatus(SignalSourceSummary summary,
            boolean awaitingMoreSamples) {
        final String text;
        if (summary == null) {
            if (trackedId == null || trackedType == null) {
                text = pluginContext.getString(R.string.tracking_none);
            } else {
                String displayName = trackedDisplayName != null
                        && !trackedDisplayName.isEmpty() ? trackedDisplayName
                                : trackedId;
                String typeLabel = getTypeLabel(trackedType);
                if (awaitingMoreSamples) {
                    text = pluginContext.getString(
                            R.string.tracking_waiting_with_type, typeLabel,
                            displayName, trackedId);
                } else {
                    text = pluginContext.getString(
                            R.string.tracking_template_with_type, typeLabel,
                            displayName, trackedId);
                }
            }
        } else {
            String displayName = getDisplayName(summary);
            String typeLabel = getTypeLabel(summary.type);
            if (awaitingMoreSamples) {
                text = pluginContext.getString(
                        R.string.tracking_waiting_with_type, typeLabel,
                        displayName, summary.id);
            } else {
                text = pluginContext.getString(
                        R.string.tracking_template_with_type, typeLabel,
                        displayName, summary.id);
            }
        }

        templateView.post(() -> trackingStatus.setText(text));
    }

    private String getDisplayName(SignalSourceSummary summary) {
        if (summary.displayName == null
                || summary.displayName.trim().isEmpty()) {
            return summary.id;
        }
        return summary.displayName;
    }

    private String formatSummary(SignalSourceSummary summary) {
        return String.format(Locale.US,
                "[%s] %s (%s)\nSamples: %d  Avg: %.1f  Best: %d  Worst: %d",
                getTypeLabel(summary.type), getDisplayName(summary),
                summary.id, summary.sampleSize, summary.averageSignal,
                summary.strongestSample, summary.weakestSample);
    }

    public void compute() {

        Log.d(TAG, "In compute");

        List<SignalSourceSummary> combined = new ArrayList<>();
        HashMap<String, List<String[]>> wifiSnapshot = wifi2cotMapComponent
                .getNodes();
        synchronized (wifiSnapshot) {
            combined.addAll(buildSignalSourceSummaries(wifiSnapshot,
                    SignalSourceType.WIFI));
        }
        HashMap<String, List<String[]>> bleSnapshot = wifi2cotMapComponent
                .getBleNodes();
        synchronized (bleSnapshot) {
            combined.addAll(buildSignalSourceSummaries(bleSnapshot,
                    SignalSourceType.BLE));
        }

        for (SignalSourceSummary summary : combined) {
            if (summary.sampleSize < MIN_SAMPLE_SIZE_FOR_DISPATCH) {
                Log.d(TAG, String.format(Locale.US,
                        "Skipping %s because it only has %d samples",
                        summary.id, summary.sampleSize));
                continue;
            }

            dispatchSignalSourceSummary(summary);
        }
    }

    public boolean isScanning() {
        return scanning;
    }

    public boolean isBleScanning() {
        return bleScanning;
    }

    private void computeBle() {
        Log.d(TAG, "In computeBle");

        HashMap<String, List<String[]>> bleSnapshot = wifi2cotMapComponent
                .getBleNodes();
        List<SignalSourceSummary> summaries;
        synchronized (bleSnapshot) {
            summaries = buildSignalSourceSummaries(bleSnapshot,
                    SignalSourceType.BLE);
        }

        for (SignalSourceSummary summary : summaries) {
            if (summary.sampleSize < MIN_SAMPLE_SIZE_FOR_DISPATCH) {
                Log.d(TAG, String.format(Locale.US,
                        "Skipping %s because it only has %d samples",
                        summary.id, summary.sampleSize));
                continue;
            }

            dispatchSignalSourceSummary(summary);
        }
    }

    private void dispatchSignalSourceSummary(SignalSourceSummary summary) {
        Log.d(TAG, String.format(Locale.US,
                "Dispatching %s at %.14f, %.14f (avg strength %.2f)",
                summary.id, summary.latitude, summary.longitude,
                summary.averageSignal));

        CotEvent cotEvent = new CotEvent();

        CoordinatedTime time = new CoordinatedTime();
        cotEvent.setTime(time);
        cotEvent.setStart(time);
        cotEvent.setStale(time.addMinutes(90));

        cotEvent.setUID(summary.getUid());

        String cotType;
        String sourceLabel;
        switch (summary.type) {
            case BLE:
                cotType = "b-l-l";
                sourceLabel = "MetaRadar";
                break;
            case WIFI:
            default:
                cotType = "a-f-G-I-E";
                sourceLabel = "wifi2cot";
                break;
        }

        cotEvent.setType(cotType);
        cotEvent.setHow("m-g");

        CotPoint cotPoint = new CotPoint(summary.latitude, summary.longitude,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN, CotPoint.UNKNOWN);
        cotEvent.setPoint(cotPoint);

        CotDetail cotDetail = new CotDetail("detail");
        cotEvent.setDetail(cotDetail);

        CotDetail contactDetail = new CotDetail("contact");
        contactDetail.setAttribute("callsign", getDisplayName(summary));
        contactDetail.setAttribute("endpoint", "0.0.0.0:4242:tcp");

        CotDetail cotRemark = new CotDetail("remarks");
        cotRemark.setAttribute("source", sourceLabel);
        cotRemark.setInnerText(String.format(Locale.US,
                "Type: %s\nName: %s\nSample size: %d\nAverage signal quality: %.2f\nBest sample: %d\nWorst sample: %d",
                getTypeLabel(summary.type), getDisplayName(summary),
                summary.sampleSize, summary.averageSignal,
                summary.strongestSample, summary.weakestSample));

        cotDetail.addChild(contactDetail);
        cotDetail.addChild(cotRemark);

        if (cotEvent.isValid())
            CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
        else
            Log.e(TAG, "cotEvent was not valid");
    }
    private List<SignalSourceSummary> buildSignalSourceSummaries(
            Map<String, List<String[]>> nodes, SignalSourceType type) {
        List<SignalSourceSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, List<String[]>> entry : nodes.entrySet()) {
            List<String[]> samples = entry.getValue();
            if (samples == null || samples.isEmpty()) {
                continue;
            }

            double weightedLatSum = 0.0;
            double weightedLngSum = 0.0;
            double weightTotal = 0.0;
            int strongestSample = Integer.MIN_VALUE;
            int weakestSample = Integer.MAX_VALUE;
            String displayName = "";

            for (String[] sample : samples) {
                int weight = Integer.parseInt(sample[0]);
                double lat = Double.parseDouble(sample[1]);
                double lng = Double.parseDouble(sample[2]);
                displayName = sample[4];

                weightTotal += weight;
                weightedLatSum += lat * weight;
                weightedLngSum += lng * weight;

                if (weight > strongestSample) {
                    strongestSample = weight;
                }
                if (weight < weakestSample) {
                    weakestSample = weight;
                }
            }

            if (weightTotal == 0.0) {
                Log.d(TAG, String.format(Locale.US,
                        "Skipping %s due to zero weight total", entry.getKey()));
                continue;
            }

            double avgSignal = weightTotal / samples.size();
            double lat = weightedLatSum / weightTotal;
            double lng = weightedLngSum / weightTotal;

            summaries.add(new SignalSourceSummary(entry.getKey(), displayName,
                    lat, lng, samples.size(), avgSignal, strongestSample,
                    weakestSample, type));
        }

        return summaries;
    }

    private String getTypeLabel(SignalSourceType type) {
        switch (type) {
            case BLE:
                return pluginContext.getString(R.string.type_ble);
            case WIFI:
            default:
                return pluginContext.getString(R.string.type_wifi);
        }
    }

    private static class SignalSourceSummary {
        final String id;
        final String displayName;
        final double latitude;
        final double longitude;
        final int sampleSize;
        final double averageSignal;
        final int strongestSample;
        final int weakestSample;
        final SignalSourceType type;

        SignalSourceSummary(String id, String displayName, double latitude,
                double longitude, int sampleSize, double averageSignal,
                int strongestSample, int weakestSample,
                SignalSourceType type) {
            this.id = id;
            this.displayName = displayName;
            this.latitude = latitude;
            this.longitude = longitude;
            this.sampleSize = sampleSize;
            this.averageSignal = averageSignal;
            this.strongestSample = strongestSample;
            this.weakestSample = weakestSample;
            this.type = type;
        }

        String getUid() {
            return type.name().toLowerCase(Locale.US) + "-" + id;
        }
    }

    private enum SignalSourceType {
        WIFI,
        BLE
    }
}
