package com.freeadbremote;

import android.graphics.Bitmap;

public class AppProcess {
    private String name;
    private String packageName;
    private boolean running;
    private Bitmap icon;

    public AppProcess(String name, String packageName, boolean running) {
        this.name = name;
        this.packageName = packageName;
        this.running = running;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public Bitmap getIcon() {
        return icon;
    }

    public void setIcon(Bitmap icon) {
        this.icon = icon;
    }
}