package com.ezstats.snapshot;

import com.ezstats.car.CarClimates;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * The JSON of a car's climate control ({@code kind: "car"}), from what MTVehicles' {@code CarClimateApi.describe} says.
 * Numbers that aren't known are left out, as everywhere in the API.
 */
final class CarJson {
    private CarJson() {}

    static JsonObject of(String license, Map<String, Object> car) {
        JsonObject json = new JsonObject();
        json.addProperty("key", CarClimates.keyOf(license));
        json.addProperty("kind", "car");
        json.addProperty("type", "car_climate");
        json.addProperty("label", text(car, "name", license));
        json.addProperty("license", license);

        boolean loaded = Boolean.TRUE.equals(car.get("loaded"));
        json.addProperty("loaded", loaded);
        json.addProperty("world", loaded ? text(car, "world", "") : "");
        json.addProperty("x", number(car, "x", 0));
        json.addProperty("y", number(car, "y", 0));
        json.addProperty("z", number(car, "z", 0));
        json.addProperty("occupied", Boolean.TRUE.equals(car.get("occupied")));

        boolean on = Boolean.TRUE.equals(car.get("on"));
        boolean running = Boolean.TRUE.equals(car.get("running"));
        String status = text(car, "status", "off");
        json.addProperty("enabled", on);
        json.addProperty("running", running);
        // The same words as a thermostat's mode, so the page colours its pill the same way.
        json.addProperty("mode", running && (status.equals("cooling") || status.equals("heating")) ? status : "idle");
        json.addProperty("climateMode", text(car, "mode", "auto"));
        json.addProperty("fan", text(car, "fan", "auto"));

        put(json, car, "targetF");
        put(json, car, "minTargetF");
        put(json, car, "maxTargetF");
        put(json, car, "cabinF");
        put(json, car, "ambientF");

        boolean remote = Boolean.TRUE.equals(car.get("remote"));
        json.addProperty("remote", remote);
        if (remote) put(json, car, "remoteSecondsLeft");
        put(json, car, "remoteStartMinutes");
        return json;
    }

    private static String text(Map<String, Object> car, String key, String fallback) {
        Object value = car.get(key);
        return value == null ? fallback : value.toString();
    }

    private static Number number(Map<String, Object> car, String key, Number fallback) {
        return car.get(key) instanceof Number n ? n : fallback;
    }

    private static void put(JsonObject json, Map<String, Object> car, String key) {
        if (car.get(key) instanceof Number n && Double.isFinite(n.doubleValue())) json.addProperty(key, n);
    }
}
