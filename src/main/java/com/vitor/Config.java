package com.vitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public List<String> brandMessages = Arrays.asList("DeepCraft");
    public long updateIntervalMillis = 5000; // tempo entre rotações em ms
    public boolean debug = false;

    // Batch sending
    public int sendBatchSize = 200; // quantos envios por execução
    public long batchDelayMillis = 50; // ms entre execuções

    // Se true, as rotações enviarão uma versão NÃO-personalizada da message
    public boolean personalizeOnConnectOnly = true;

    // Transient para evitar serialização pelo Gson
    private transient File configFile;

    public Config(File configFile) {
        this.configFile = configFile;
    }

    public boolean init() {
        boolean modified = false;

        if (brandMessages == null || brandMessages.isEmpty()) {
            brandMessages = Arrays.asList("DeepCraft");
            modified = true;
        }

        if (updateIntervalMillis < 1000) {
            updateIntervalMillis = 5000;
            modified = true;
        }

        if (sendBatchSize <= 0) {
            sendBatchSize = 200;
            modified = true;
        }

        if (batchDelayMillis < 10) {
            batchDelayMillis = 50;
            modified = true;
        }

        return modified;
    }

    public void save() throws IOException {
        if (configFile == null) {
            throw new IllegalStateException("Config file not set");
        }

        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(this, writer);
        }
    }

    public static Config load(File configFile) throws IOException {
        if (!configFile.exists()) {
            Config config = new Config(configFile);
            config.init();
            config.save();
            return config;
        }

        try (FileReader reader = new FileReader(configFile)) {
            Config config = GSON.fromJson(reader, Config.class);
            config.configFile = configFile;
            config.init();
            return config;
        }
    }
}