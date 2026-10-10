package se.alipsa.gradle.plugin.release

import org.gradle.api.GradleException

class TimeoutSettings {
    static int validate(String name, int value) {
        if (value < 1 || value > 86400) {
            throw new GradleException("${name} must be between 1 and 86400 seconds")
        }
        Math.multiplyExact((long) value, 1000L)
        return value
    }
}
