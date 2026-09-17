package org.las2mile.scrcpy.protocol;

public class Position {
    private final int x;
    private final int y;
    private final int screenWidth;
    private final int screenHeight;

    public Position(int x, int y, int screenWidth, int screenHeight) {
        this.x = x;
        this.y = y;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }
}
