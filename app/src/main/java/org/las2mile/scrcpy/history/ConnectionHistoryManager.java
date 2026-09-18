package org.las2mile.scrcpy.history;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ConnectionHistoryManager {

    private static final String PREF_NAME = "scrcpy_prefs";
    private static final String KEY_HISTORY = "pref_connection_history_json";
    private static final int MAX_HISTORY_ITEMS = 20;

    public static class HistoryItem {
        public String address;
        public String type; // "wifi" or "usb"
        public String displayName;
        public long timestamp;

        public HistoryItem(String address, String type, String displayName, long timestamp) {
            this.address = address;
            this.type = type;
            this.displayName = displayName;
            this.timestamp = timestamp;
        }
    }

    private static ConnectionHistoryManager instance;
    private final SharedPreferences prefs;

    private ConnectionHistoryManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized ConnectionHistoryManager getInstance(Context context) {
        if (instance == null) {
            instance = new ConnectionHistoryManager(context);
        }
        return instance;
    }

    public synchronized void addHistory(String address, String type, String displayName) {
        if (TextUtils.isEmpty(address)) return;

        List<HistoryItem> list = getHistoryList();
        // Remove existing item with same address if present
        for (int i = 0; i < list.size(); i++) {
            if (address.equalsIgnoreCase(list.get(i).address)) {
                list.remove(i);
                break;
            }
        }

        // Add to top
        list.add(0, new HistoryItem(address, type, displayName, System.currentTimeMillis()));

        // Trim to MAX_HISTORY_ITEMS
        if (list.size() > MAX_HISTORY_ITEMS) {
            list = list.subList(0, MAX_HISTORY_ITEMS);
        }

        saveList(list);
    }

    public synchronized List<HistoryItem> getHistoryList() {
        List<HistoryItem> list = new ArrayList<>();
        String jsonStr = prefs.getString(KEY_HISTORY, null);
        if (TextUtils.isEmpty(jsonStr)) {
            return list;
        }

        try {
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String address = obj.optString("address");
                String type = obj.optString("type", "wifi");
                String displayName = obj.optString("displayName", address);
                long timestamp = obj.optLong("timestamp", 0);
                if (!TextUtils.isEmpty(address)) {
                    list.add(new HistoryItem(address, type, displayName, timestamp));
                }
            }
        } catch (Exception ignored) {}

        return list;
    }

    public synchronized List<String> getRecentAddresses(int maxCount) {
        List<String> addresses = new ArrayList<>();
        List<HistoryItem> list = getHistoryList();
        for (HistoryItem item : list) {
            if ("wifi".equals(item.type) && !TextUtils.isEmpty(item.address)) {
                addresses.add(item.address);
                if (addresses.size() >= maxCount) {
                    break;
                }
            }
        }
        return addresses;
    }

    public synchronized void deleteHistory(String address) {
        if (TextUtils.isEmpty(address)) return;
        List<HistoryItem> list = getHistoryList();
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            if (address.equalsIgnoreCase(list.get(i).address)) {
                list.remove(i);
                changed = true;
                break;
            }
        }
        if (changed) {
            saveList(list);
        }
    }

    public synchronized void clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply();
    }

    private void saveList(List<HistoryItem> list) {
        try {
            JSONArray arr = new JSONArray();
            for (HistoryItem item : list) {
                JSONObject obj = new JSONObject();
                obj.put("address", item.address);
                obj.put("type", item.type);
                obj.put("displayName", item.displayName);
                obj.put("timestamp", item.timestamp);
                arr.put(obj);
            }
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }
}
