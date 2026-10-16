package com.koper.greenmarine.api.pack;

public record PackParameter(String name, float defaultValue, float min, float max) {
    public PackParameter {
        if (name == null || !name.matches("[a-zA-Z_][a-zA-Z0-9_]{0,47}")
            || !Float.isFinite(defaultValue) || !Float.isFinite(min) || !Float.isFinite(max)
            || min > max || defaultValue < min || defaultValue > max) {
            throw new IllegalArgumentException("Invalid pack parameter");
        }
    }

    public float validate(float value) {
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException("Parameter outside declared range: " + name);
        }
        return value;
    }
}
