
package com.atakmap.android.wifi2cot;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
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

    private Timer timer;

    private static final int MIN_SAMPLE_SIZE_FOR_DISPATCH = 3;

    private boolean scanning = false;

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
    }

    /**************************** PUBLIC METHODS *****************************/

    public void disposeImpl() {
        if (timer != null) {
            timer.cancel();
            timer = null;
            scanning = false;
        }
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
                wifi2cotMapComponent.getNodes().clear();
                timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (!mc.getWifiManager().startScan()) {
                            Log.d(TAG, "Scan failed");
                        }
                    }
                },0, 5000);
            });

            stop.setOnClickListener(view -> {
                scanning = false;
                if (timer != null) {
                    timer.cancel();
                    timer = null;
                }
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
        if (timer != null) {
            timer.cancel();
            timer = null;
            scanning = false;
        }
    }

    public void compute() {

        Log.d(TAG, "In compute");

        HashMap<String, List<String[]>> nodes = wifi2cotMapComponent.getNodes();

        List<AccessPointSummary> summaries = buildAccessPointSummaries(nodes);
        for (AccessPointSummary summary : summaries) {
            if (summary.sampleSize < MIN_SAMPLE_SIZE_FOR_DISPATCH) {
                Log.d(TAG, String.format(Locale.US,
                        "Skipping %s because it only has %d samples", summary.bssid,
                        summary.sampleSize));
                continue;
            }

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
