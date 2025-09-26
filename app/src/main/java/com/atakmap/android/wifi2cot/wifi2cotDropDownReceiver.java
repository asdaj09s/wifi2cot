
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

    private final Button start, stop, guess;
    private final ListView scanList;
    private final TextView trackingStatus;

    private Timer scanTimer;
    private Timer uiUpdateTimer;
    private Timer trackTimer;

    private static final int MIN_SAMPLE_SIZE_FOR_DISPATCH = 3;

    private boolean scanning = false;
    private final ArrayAdapter<String> scanListAdapter;
    private final List<AccessPointSummary> currentSummaries = new ArrayList<>();
    private String trackedBssid;
    private String trackedDisplayName;

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
        scanList = templateView.findViewById(R.id.scan_list);
        trackingStatus = templateView.findViewById(R.id.tracking_status);

        scanListAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_list_item_activated_1,
                new ArrayList<>());
        scanList.setAdapter(scanListAdapter);
        scanList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        scanList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position,
                    long id) {
                AccessPointSummary summary;
                synchronized (currentSummaries) {
                    if (position < 0 || position >= currentSummaries.size()) {
                        return;
                    }
                    summary = currentSummaries.get(position);
                }

                if (summary == null) {
                    return;
                }

                if (summary.bssid.equals(trackedBssid)) {
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
        stopTracking();
        scanning = false;
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

            start.setOnClickListener(view -> {
                Log.d(TAG, "Starting scan");
                scanning = true;
                Toast.makeText(MapView._mapView.getContext(), "Starting scan",
                        Toast.LENGTH_LONG).show();
                synchronized (wifi2cotMapComponent.getNodes()) {
                    wifi2cotMapComponent.getNodes().clear();
                }
                resetScanList();
                cancelScanTimer();
                cancelUiTimer();
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
        stopTracking();
        scanning = false;
    }

    private void cancelScanTimer() {
        if (scanTimer != null) {
            scanTimer.cancel();
            scanTimer = null;
        }
    }

    private void cancelUiTimer() {
        if (uiUpdateTimer != null) {
            uiUpdateTimer.cancel();
            uiUpdateTimer = null;
        }
    }

    private void stopTracking() {
        trackedBssid = null;
        trackedDisplayName = null;
        if (trackTimer != null) {
            trackTimer.cancel();
            trackTimer = null;
        }
        templateView.post(() -> {
            trackingStatus.setText(pluginContext.getString(R.string.tracking_none));
            scanList.clearChoices();
        });
    }

    private void startTracking(AccessPointSummary summary, int position) {
        trackedBssid = summary.bssid;
        trackedDisplayName = getDisplayName(summary);
        if (trackTimer != null) {
            trackTimer.cancel();
        }
        trackTimer = new Timer();
        trackTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                dispatchTrackedAccessPoint();
            }
        }, 0, 5000);

        templateView.post(() -> {
            scanList.setItemChecked(position, true);
            updateTrackingStatus(summary,
                    summary.sampleSize < MIN_SAMPLE_SIZE_FOR_DISPATCH);
            String message = pluginContext.getString(R.string.tracking_template,
                    getDisplayName(summary), summary.bssid);
            Toast.makeText(MapView._mapView.getContext(), message,
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void refreshScanSummaries() {
        HashMap<String, List<String[]>> nodeSnapshot = wifi2cotMapComponent.getNodes();
        List<AccessPointSummary> summaries;
        synchronized (nodeSnapshot) {
            summaries = buildAccessPointSummaries(nodeSnapshot);
        }

        final List<String> formatted = new ArrayList<>();
        for (AccessPointSummary summary : summaries) {
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

            if (trackedBssid != null) {
                int index = findSummaryIndex(trackedBssid);
                if (index >= 0) {
                    scanList.setItemChecked(index, true);
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

    private void dispatchTrackedAccessPoint() {
        AccessPointSummary summary = getSummaryForTrackedBssid();
        if (summary == null) {
            if (trackedBssid != null) {
                updateTrackingStatus(null, true);
            } else {
                updateTrackingStatus(null, false);
            }
            return;
        }

        trackedDisplayName = getDisplayName(summary);
        if (summary.sampleSize < MIN_SAMPLE_SIZE_FOR_DISPATCH) {
            updateTrackingStatus(summary, true);
            return;
        }

        updateTrackingStatus(summary, false);
        dispatchAccessPointSummary(summary);
    }

    private AccessPointSummary getSummaryForTrackedBssid() {
        if (trackedBssid == null) {
            return null;
        }
        synchronized (currentSummaries) {
            for (AccessPointSummary summary : currentSummaries) {
                if (summary.bssid.equals(trackedBssid)) {
                    return summary;
                }
            }
        }
        return null;
    }

    private int findSummaryIndex(String bssid) {
        synchronized (currentSummaries) {
            for (int i = 0; i < currentSummaries.size(); i++) {
                if (currentSummaries.get(i).bssid.equals(bssid)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void updateTrackingStatus(AccessPointSummary summary, boolean awaitingMoreSamples) {
        final String text;
        if (summary == null) {
            if (trackedBssid == null) {
                text = pluginContext.getString(R.string.tracking_none);
            } else {
                String displayName = trackedDisplayName != null ? trackedDisplayName
                        : trackedBssid;
                if (awaitingMoreSamples) {
                    text = pluginContext.getString(R.string.tracking_waiting, displayName,
                            trackedBssid);
                } else {
                    text = pluginContext.getString(R.string.tracking_template, displayName,
                            trackedBssid);
                }
            }
        } else {
            String displayName = getDisplayName(summary);
            if (awaitingMoreSamples) {
                text = pluginContext.getString(R.string.tracking_waiting, displayName,
                        summary.bssid);
            } else {
                text = pluginContext.getString(R.string.tracking_template, displayName,
                        summary.bssid);
            }
        }

        templateView.post(() -> trackingStatus.setText(text));
    }

    private String getDisplayName(AccessPointSummary summary) {
        if (summary.ssid == null || summary.ssid.trim().isEmpty()) {
            return summary.bssid;
        }
        return summary.ssid;
    }

    private String formatSummary(AccessPointSummary summary) {
        return String.format(Locale.US,
                "%s (%s)\nSamples: %d  Avg: %.1f  Best: %d  Worst: %d",
                getDisplayName(summary), summary.bssid, summary.sampleSize,
                summary.averageSignal, summary.strongestSample,
                summary.weakestSample);
    }

    public void compute() {

        Log.d(TAG, "In compute");

        HashMap<String, List<String[]>> nodes = wifi2cotMapComponent.getNodes();

        List<AccessPointSummary> summaries;
        synchronized (nodes) {
            summaries = buildAccessPointSummaries(nodes);
        }
        for (AccessPointSummary summary : summaries) {
            if (summary.sampleSize < MIN_SAMPLE_SIZE_FOR_DISPATCH) {
                Log.d(TAG, String.format(Locale.US,
                        "Skipping %s because it only has %d samples", summary.bssid,
                        summary.sampleSize));
                continue;
            }

            dispatchAccessPointSummary(summary);
        }
    }

    private void dispatchAccessPointSummary(AccessPointSummary summary) {
        Log.d(TAG, String.format(Locale.US,
                "Dispatching %s at %.14f, %.14f (avg strength %.2f)",
                summary.bssid, summary.latitude, summary.longitude,
                summary.averageSignal));

        CotEvent cotEvent = new CotEvent();

        CoordinatedTime time = new CoordinatedTime();
        cotEvent.setTime(time);
        cotEvent.setStart(time);
        cotEvent.setStale(time.addMinutes(90));

        cotEvent.setUID(summary.bssid);

        cotEvent.setType("a-f-G-I-E");

        cotEvent.setHow("m-g");

        CotPoint cotPoint = new CotPoint(summary.latitude, summary.longitude,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN, CotPoint.UNKNOWN);
        cotEvent.setPoint(cotPoint);

        CotDetail cotDetail = new CotDetail("detail");
        cotEvent.setDetail(cotDetail);

        CotDetail contactDetail = new CotDetail("contact");
        contactDetail.setAttribute("callsign", summary.ssid);
        contactDetail.setAttribute("endpoint", "0.0.0.0:4242:tcp");

        CotDetail cotRemark = new CotDetail("remarks");
        cotRemark.setAttribute("source", "wifi2cot");
        cotRemark.setInnerText(String.format(Locale.US,
                "SSID: %s\nSample size: %d\nAverage signal quality: %.2f\nBest sample: %d\nWorst sample: %d",
                summary.ssid, summary.sampleSize, summary.averageSignal,
                summary.strongestSample, summary.weakestSample));

        cotDetail.addChild(contactDetail);
        cotDetail.addChild(cotRemark);

        if (cotEvent.isValid())
            CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
        else
            Log.e(TAG, "cotEvent was not valid");
    }

    public boolean isScanning() {
        return scanning;
    }

    private List<AccessPointSummary> buildAccessPointSummaries(
            Map<String, List<String[]>> nodes) {
        List<AccessPointSummary> summaries = new ArrayList<>();
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
            String ssid = "";

            for (String[] sample : samples) {
                int weight = Integer.parseInt(sample[0]);
                double lat = Double.parseDouble(sample[1]);
                double lng = Double.parseDouble(sample[2]);
                ssid = sample[4];

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

            summaries.add(new AccessPointSummary(entry.getKey(), ssid, lat, lng,
                    samples.size(), avgSignal, strongestSample, weakestSample));
        }

        return summaries;
    }

    private static class AccessPointSummary {
        final String bssid;
        final String ssid;
        final double latitude;
        final double longitude;
        final int sampleSize;
        final double averageSignal;
        final int strongestSample;
        final int weakestSample;

        AccessPointSummary(String bssid, String ssid, double latitude,
                double longitude, int sampleSize, double averageSignal,
                int strongestSample, int weakestSample) {
            this.bssid = bssid;
            this.ssid = ssid;
            this.latitude = latitude;
            this.longitude = longitude;
            this.sampleSize = sampleSize;
            this.averageSignal = averageSignal;
            this.strongestSample = strongestSample;
            this.weakestSample = weakestSample;
        }
    }
}
