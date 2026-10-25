package hr.chyaro.lib.purpur;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hr.chyaro.lib.ChyaroLib;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

public class PurpurDownloader {

    private final ChyaroLib plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public PurpurDownloader(ChyaroLib plugin) {
        this.plugin = plugin;
    }

    public String getCurrentMinecraftVersion() {
        try {
            String version = Bukkit.getMinecraftVersion();
            if (version != null && !version.isEmpty()) {
                return version;
            }
        } catch (Throwable ignored) {
        }
        // Fallback: parse Bukkit.getBukkitVersion() e.g. "1.21.4-R0.1-SNAPSHOT" -> "1.21.4"
        String bukkitVersion = Bukkit.getBukkitVersion();
        if (bukkitVersion != null && bukkitVersion.contains("-")) {
            return bukkitVersion.split("-")[0];
        }
        return bukkitVersion;
    }

    public void downloadLatestPurpur(CommandSender sender, String requestedVersion) {
        final String version = (requestedVersion != null && !requestedVersion.isBlank()) 
                ? requestedVersion.trim() 
                : getCurrentMinecraftVersion();

        int timeoutSeconds = plugin.getConfig().getInt("download.connect-timeout-seconds", 15);
        String outputDir = plugin.getConfig().getString("download.output-directory", ".");
        String outputFilename = plugin.getConfig().getString("download.output-filename", "server.jar");

        sendMessage(sender, "<green>[chyarolib]</green> <gray>Checking latest Purpur build for Minecraft <yellow>" + version + "</yellow>...</gray>");

        // Execute download asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();

                // 1. Fetch version metadata from Purpur API
                String apiUri = "https://api.purpurmc.org/v2/purpur/" + version;
                HttpRequest apiRequest = HttpRequest.newBuilder()
                        .uri(URI.create(apiUri))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("User-Agent", "chyarolib/1.0 (Minecraft Server Purpur Downloader)")
                        .GET()
                        .build();

                HttpResponse<String> apiResponse = client.send(apiRequest, HttpResponse.BodyHandlers.ofString());

                if (apiResponse.statusCode() != 200) {
                    sendMessage(sender, "<red>[chyarolib] Failed to retrieve Purpur build info for version " + version + " (HTTP " + apiResponse.statusCode() + ")</red>");
                    plugin.getLogger().warning("Failed to query Purpur API: HTTP " + apiResponse.statusCode() + " body: " + apiResponse.body());
                    return;
                }

                JsonObject json = JsonParser.parseString(apiResponse.body()).getAsJsonObject();
                if (!json.has("builds") || !json.getAsJsonObject("builds").has("latest")) {
                    sendMessage(sender, "<red>[chyarolib] Purpur API response for version " + version + " did not contain valid build information.</red>");
                    return;
                }

                String latestBuild = json.getAsJsonObject("builds").get("latest").getAsString();
                String downloadUrl = "https://api.purpurmc.org/v2/purpur/" + version + "/" + latestBuild + "/download";

                sendMessage(sender, "<green>[chyarolib]</green> <gray>Found Purpur build <yellow>#" + latestBuild + "</yellow>. Starting download to <yellow>" + outputFilename + "</yellow>...</gray>");
                plugin.getLogger().info("Downloading Purpur build #" + latestBuild + " for MC " + version + " from " + downloadUrl);

                // 2. Prepare file locations
                File targetDirectory = new File(outputDir);
                if (!targetDirectory.exists()) {
                    targetDirectory.mkdirs();
                }

                File finalFile = new File(targetDirectory, outputFilename);
                File tempFile = new File(targetDirectory, outputFilename + ".tmp");

                // 3. Download the JAR file to a temporary file
                HttpRequest downloadRequest = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .timeout(Duration.ofSeconds(60))
                        .header("User-Agent", "chyarolib/1.0 (Minecraft Server Purpur Downloader)")
                        .GET()
                        .build();

                HttpResponse<InputStream> downloadResponse = client.send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream());

                if (downloadResponse.statusCode() != 200) {
                    sendMessage(sender, "<red>[chyarolib] Failed to download Purpur jar file (HTTP " + downloadResponse.statusCode() + ")</red>");
                    return;
                }

                long contentLength = downloadResponse.headers().firstValueAsLong("Content-Length").orElse(-1L);

                try (InputStream in = downloadResponse.body();
                     FileOutputStream out = new FileOutputStream(tempFile)) {

                    byte[] buffer = new byte[16384];
                    long totalBytesRead = 0;
                    int bytesRead;
                    long lastLogTime = System.currentTimeMillis();
                    int lastReportedPercent = -1;

                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;

                        long now = System.currentTimeMillis();
                        if (contentLength > 0) {
                            int percent = (int) ((totalBytesRead * 100) / contentLength);
                            if (percent % 25 == 0 && percent != lastReportedPercent && now - lastLogTime > 1500) {
                                lastReportedPercent = percent;
                                lastLogTime = now;
                                double mb = totalBytesRead / (1024.0 * 1024.0);
                                sendMessage(sender, "<green>[chyarolib]</green> <gray>Downloading Purpur jar... <yellow>" + percent + "%</yellow> (" + String.format("%.2f", mb) + " MB)</gray>");
                            }
                        }
                    }
                    out.flush();
                }

                // 4. Move temp file to final location server.jar
                Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

                double totalMB = finalFile.length() / (1024.0 * 1024.0);
                String successMsg = "<green>[chyarolib] Successfully downloaded latest Purpur jar for MC " + version + " (Build #" + latestBuild + ", " + String.format("%.2f", totalMB) + " MB) to " + finalFile.getName() + "!</green>";
                sendMessage(sender, successMsg);
                plugin.getLogger().info("Successfully updated Purpur jar to build #" + latestBuild + " at " + finalFile.getAbsolutePath());

            } catch (Exception e) {
                sendMessage(sender, "<red>[chyarolib] Error downloading Purpur jar: " + e.getMessage() + "</red>");
                plugin.getLogger().severe("Error downloading Purpur jar: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void sendMessage(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(miniMessage.deserialize(message));
        }
    }
}
