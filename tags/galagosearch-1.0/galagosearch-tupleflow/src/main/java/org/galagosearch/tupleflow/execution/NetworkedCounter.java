package org.galagosearch.tupleflow.execution;

import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import org.galagosearch.tupleflow.Counter;

public class NetworkedCounter implements Counter {
    long count = 0;
    long lastFlushCount = Long.MIN_VALUE;
    String counterName;
    String stageName;
    String instance;
    String url;

    NetworkedCounter(String counterName, String stageName, String instance, String url) {
        super();
        this.counterName = counterName;
        this.stageName = stageName;
        this.instance = instance;
        this.url = url;
    }

    public void increment() {
        incrementBy(1);
    }

    public void incrementBy(int value) {
        count += value;
    }

    public void flush() {
        // No need to send updates for counters that aren't changing.
        if (lastFlushCount == count)
            return;

        try {
            String fullUrl = String.format("%s/setcounter?counterName=%s&stageName=%s&instance=%s&value=%d",
                                           url, URLEncoder.encode(counterName, "UTF-8"),
                                           URLEncoder.encode(stageName, "UTF-8"),
                                           URLEncoder.encode(instance, "UTF-8"), count);
            URLConnection connection = new URL(fullUrl).openConnection();
            connection.connect();
            connection.getInputStream().close();
            connection.getOutputStream().close();
            lastFlushCount = count;
        } catch (Exception e) {
        }
    }
}
