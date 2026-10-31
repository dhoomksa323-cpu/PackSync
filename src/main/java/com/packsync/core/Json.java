package com.packsync.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared JSON helpers (Gson ships with Minecraft on both sides). */
public final class Json {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Json() {}

    public static <T> T read(Path file, Class<T> type) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1); // strip BOM (Notepad)
        T value = GSON.fromJson(text, type);
        if (value == null) throw new IOException("Empty JSON: " + file);
        return value;
    }

    public static void write(Path file, Object value) throws IOException {
        IO.atomicWrite(file, GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }
}
