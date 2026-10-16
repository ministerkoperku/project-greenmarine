package com.koper.greenmarine.api;

public record Light(double x, double y, double z, float red, float green, float blue,
                    float radius, float intensity) {
    public Light {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !channel(red) || !channel(green) || !channel(blue)
            || !Float.isFinite(radius) || radius <= 0
            || !Float.isFinite(intensity) || intensity < 0) {
            throw new IllegalArgumentException("Invalid light position, color, radius or intensity");
        }
    }

    private static boolean channel(float value) {
        return Float.isFinite(value) && value >= 0 && value <= 1;
    }
}
