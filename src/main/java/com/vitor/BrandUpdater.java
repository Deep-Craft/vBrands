package com.vitor;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class BrandUpdater {
    private final vBrand plugin;
    private final ProxyServer proxy;
    private final Config config;

    // Tarefas agendadas: uma para trocar o índice (rotation) e outra para processar a fila em batches
    private ScheduledTask rotationTask;
    private ScheduledTask batchTask;

    // Índice atual de brand
    private final AtomicInteger currentBrandIndex = new AtomicInteger(0);

    // Fila de envio (snapshot dos players que devem receber a brand atual)
    private final Queue<Player> sendQueue = new ConcurrentLinkedQueue<>();

    // Cache de mensagens já codificadas (chave = mensagem final -> valor = bytes prontos pra enviar)
    // Mantemos um ConcurrentHashMap simples; para produção pode-se usar um cache com tamanho máximo/LRU.
    private final ConcurrentHashMap<String, byte[]> encodedCache = new ConcurrentHashMap<>();

    // Indica se o batchTask já está ativo (para evitar re-agendamento desnecessário)
    private final AtomicBoolean batchRunning = new AtomicBoolean(false);

    public BrandUpdater(vBrand plugin, ProxyServer proxy, Config config) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.config = config;

        startScheduler();
    }

    private void startScheduler() {
        // Agendamos a rotação das mensagens apenas se houver mais de uma mensagem e intervalo válido
        if (config.brandMessages.size() > 1 && config.updateIntervalMillis > 0) {
            rotationTask = proxy.getScheduler()
                    .buildTask(plugin, this::cycleAndQueueBrand)
                    .delay(config.updateIntervalMillis, TimeUnit.MILLISECONDS)
                    .repeat(config.updateIntervalMillis, TimeUnit.MILLISECONDS)
                    .schedule();
        }
    }

    /**
     * Incrementa o índice da brand e enfileira os jogadores para envio da nova brand.
     * Não envia imediatamente para todos: apenas popula a fila e deixa o batchTask escoar.
     */
    private void cycleAndQueueBrand() {
        int idx = currentBrandIndex.updateAndGet(i -> (i + 1) % config.brandMessages.size());
        if (config.debug) plugin.logger().info("Rotating brand to index {} -> {}", idx, config.brandMessages.get(idx));
        queueAllPlayersForCurrentBrand();
    }

    /**
     * Coloca todos os jogadores online na fila para receberem a brand atual.
     * Se a opção personalizeOnConnectOnly estiver ativa, a rotação enviará uma versão não-personalizada
     * da message (ou seja, {player} e {server} não serão substituídos na rotação), reduzindo encodings.
     */
    private void queueAllPlayersForCurrentBrand() {
        // Snapshot para evitar concorrência e mudança de lista durante iteração
        List<Player> snapshot = new ArrayList<>();
        proxy.getAllPlayers().forEach(snapshot::add);

        if (snapshot.isEmpty()) return;

        if (config.debug) plugin.logger().info("Queueing {} players for brand broadcast (index={})", snapshot.size(), currentBrandIndex.get());

        // Enfileira os players
        snapshot.forEach(sendQueue::add);

        // Inicia (ou garante que esteja rodando) o batchTask que processará a fila
        ensureBatchTaskRunning();
    }

    private void ensureBatchTaskRunning() {
        if (batchRunning.compareAndSet(false, true)) {
            batchTask = proxy.getScheduler()
                    .buildTask(plugin, this::processBatch)
                    // executa repetidamente com o delay configurado. Se a fila acabar, cancela.
                    .delay(config.batchDelayMillis, TimeUnit.MILLISECONDS)
                    .repeat(config.batchDelayMillis, TimeUnit.MILLISECONDS)
                    .schedule();
        }
    }

    private void processBatch() {
        int sent = 0;
        int batchSize = config.sendBatchSize;

        while (sent < batchSize) {
            Player p = sendQueue.poll();
            if (p == null) break;
            try {
                // Se a configuração personalizeOnConnectOnly estiver true, NÃO personalizamos aqui durante a rotação.
                boolean personalize = !(config.personalizeOnConnectOnly && isRotationContext());
                sendBrandInternal(p, personalize);
            } catch (Exception e) {
                if (config.debug) plugin.logger().error("Error sending brand to {}: {}", p.getUsername(), e.getMessage());
            }
            sent++;
        }

        // Se a fila estiver vazia, cancelamos a tarefa até a próxima rotação/trigger
        if (sendQueue.isEmpty()) {
            if (batchTask != null) {
                batchTask.cancel();
                batchTask = null;
            }
            batchRunning.set(false);
        }
    }

    // Pequeno heuristic: quando chamamos a partir da rotação, assumimos contexto de rotação.
    // Se quisermos distinção mais explícita, poderíamos passar um flag quando enfileirar.
    private boolean isRotationContext() {
        // Aqui sempre true quando a fila tiver sido preenchida via queueAllPlayersForCurrentBrand.
        // Em envios individuais (ex: onPlayerConnect) chamamos sendBrand() diretamente (veja abaixo).
        return true;
    }

    /**
     * Envia imediatamente a brand para um jogador específico (usado no connect).
     * Esta chamada pode ser usada para enviar uma versão personalizada contendo {player} e {server}.
     */
    public void sendBrand(Player player) {
        if (player == null || !player.isActive()) return;
        sendBrandInternal(player, true);
    }

    private void sendBrandInternal(Player player, boolean personalize) {
        if (player == null || !player.isActive()) return;

        String brandMessage = getCurrentBrandMessage(player, personalize);

        // Tenta pegar do cache; se não existir, codifica e armazena
        byte[] encoded = encodedCache.get(brandMessage);
        if (encoded == null) {
            encoded = encodeBrandString(brandMessage);
            // Coloca no cache; pequenos riscos de memória se muitos valores diferentes aparecerem
            // No futuro, trocar para um cache LRU de tamanho limitado.
            encodedCache.put(brandMessage, encoded);
        }

        // Envia diretamente o array de bytes (Velocity aceita byte[] para plugin channels)
        player.sendPluginMessage(vBrand.BRAND_IDENTIFIER, encoded);

        if (config.debug) {
            plugin.logger().info("Sent brand '{}' to player {} (personalize={})", brandMessage, player.getUsername(), personalize);
        }
    }

    /**
     * Construção da string final que será enviada ao cliente.
     * Se personalize == false, não substituirá {player} e {server} (útil para rotações em massa).
     */
    private String getCurrentBrandMessage(Player player, boolean personalize) {
        plugin.logger().info("getCurrentBrandMessage() called for player=" + (player != null ? player.getUsername() : "null") + ", personalize=" + personalize);
        String rawMessage = config.brandMessages.get(currentBrandIndex.get());

        String result = rawMessage
                .replace("{online}", String.valueOf(proxy.getPlayerCount()))
                .replace("{max_players}", String.valueOf(proxy.getConfiguration().getShowMaxPlayers()));

        if (personalize && player != null) {
            result = result
                    .replace("{player}", player.getUsername())
                    .replace("{server}", getPlayerServerName(player));
        }

        return result;
    }

    private String getPlayerServerName(Player player) {
        Optional<ServerConnection> currentServer = player.getCurrentServer();
        return currentServer.map(serverConnection -> serverConnection.getServerInfo().getName())
                .orElse("Unknown");
    }

    /**
     * Codifica a string do brand no formato do protocolo Minecraft: [VarInt length] + [UTF-8 bytes]
     * Implementação sem Netty para reduzir dependências de alocação de ByteBuf. Retorna um novo byte[] pronto.
     */
    private byte[] encodeBrandString(String brand) {
        byte[] brandBytes = brand.getBytes(StandardCharsets.UTF_8);

        // VarInt pode ocupar até 5 bytes
        ByteArrayOutputStream out = new ByteArrayOutputStream(brandBytes.length + 5);

        // Escreve o VarInt do comprimento
        writeVarInt(out, brandBytes.length);
        // Escreve os bytes da string
        out.write(brandBytes, 0, brandBytes.length);

        return out.toByteArray();
    }

    /**
     * Escreve um VarInt em um ByteArrayOutputStream
     */
    private void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /**
     * Para parar limpamente as tasks e liberar recursos.
     */
    public void stop() {
        if (rotationTask != null) {
            rotationTask.cancel();
            rotationTask = null;
        }
        if (batchTask != null) {
            batchTask.cancel();
            batchTask = null;
        }
        sendQueue.clear();
        encodedCache.clear();
        batchRunning.set(false);
    }

    /**
     * Permite forçar um índice específico (útil para debug)
     */
    public void setBrandIndex(int index) {
        if (index >= 0 && index < config.brandMessages.size()) {
            currentBrandIndex.set(index);
        }
    }

    /**
     * Chama broadcast: enfileira todos os jogadores para o envio da brand atual (não personaliza por padrão se personalizeOnConnectOnly true)
     */
    public void broadcastBrand() {
        queueAllPlayersForCurrentBrand();
    }
}
