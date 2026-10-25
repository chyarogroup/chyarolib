package hr.chyaro.lib.version;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hr.chyaro.lib.ChyaroLib;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Handles checking Modrinth API for GenSprout plugin version updates.
 * Channel aware: ignores pre-release / beta versions if the running plugin is on the release channel.
 */
public class GenSproutVersionChecker {

    private final ChyaroLib plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private String latestAvailableVersion = null;
    private String modrinthUrl = "https://modrinth.com/plugin/gensprout";
    private boolean updateAvailable = false;

    public GenSproutVersionChecker(ChyaroLib plugin) {
        this.plugin = plugin;
    }

    public void checkVersion(CommandSender sender, boolean quietIfLatest) {
        Plugin genSprout = Bukkit.getPluginManager().getPlugin("GenSprout");
        String currentVersion = (genSprout != null) ? genSprout.getDescription().getVersion() : "1.3.0-beta1";

        boolean currentIsBeta = isBetaVersion(currentVersion);

        int timeoutSeconds = plugin.getConfig().getInt("version-check.connect-timeout-seconds", 15);
        String projectSlug = plugin.getConfig().getString("version-check.project-slug", "gensprout");
        modrinthUrl = plugin.getConfig().getString("version-check.modrinth-url", "https://modrinth.com/plugin/gensprout");

        sendMessage(sender, "<green>[chyarolib]</green> <gray>Checking Modrinth API for GenSprout updates...</gray>");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();

                String apiUri = "https://api.modrinth.com/v2/project/" + projectSlug + "/version";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUri))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("User-Agent", "chyarolib/1.0.0 (Minecraft Server Plugin Update Checker)")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    sendMessage(sender, "<red>[chyarolib] Failed to check Modrinth API for GenSprout (HTTP " + response.statusCode() + ")</red>");
                    return;
                }

                JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();
                String newestMatchingVersion = null;
                String newestVersionType = null;

                for (JsonElement elem : versions) {
                    if (!elem.isJsonObject()) continue;
                    JsonObject verObj = elem.getAsJsonObject();
                    String versionNum = verObj.has("version_number") ? verObj.get("version_number").getAsString() : "";
                    String versionType = verObj.has("version_type") ? verObj.get("version_type").getAsString() : "release";

                    // Release channel check: skip pre-release/beta builds if current version is stable release
                    if (!currentIsBeta && !versionType.equalsIgnoreCase("release")) {
                        continue;
                    }

                    if (newestMatchingVersion == null) {
                        newestMatchingVersion = versionNum;
                        newestVersionType = versionType;
                        break; // Modrinth API returns versions ordered newest first
                    }
                }

                if (newestMatchingVersion != null) {
                    this.latestAvailableVersion = newestMatchingVersion;
                    int comparison = compareVersions(newestMatchingVersion, currentVersion);
                    if (comparison > 0) {
                        this.updateAvailable = true;
                        String msg = "<green>[chyarolib]</green> <gold>A new GenSprout update is available!</gold> <gray>(Current: <yellow>v" 
                                + currentVersion + "</yellow> | Latest: <green>v" + newestMatchingVersion + "</green> [" + newestVersionType + "])</gray>\n"
                                + "<gray>Download at: </gray><click:open_url:'" + modrinthUrl + "'><hover:show_text:'<gray>Click to open Modrinth page</gray>'><underlined><aqua>" 
                                + modrinthUrl + "</aqua></underlined></hover></click>";
                        
                        sendMessage(sender, msg);
                        plugin.getLogger().info("GenSprout update available on Modrinth: v" + newestMatchingVersion + " (Current: v" + currentVersion + ")");
                    } else {
                        this.updateAvailable = false;
                        if (sender != null || !quietIfLatest) {
                            String msg = "<green>[chyarolib]</green> <gray>GenSprout is up to date (<yellow>v" + currentVersion + "</yellow>).</gray>";
                            sendMessage(sender, msg);
                        }
                    }
                }
            } catch (Exception e) {
                sendMessage(sender, "<red>[chyarolib] Error checking GenSprout updates: " + e.getMessage() + "</red>");
                plugin.getLogger().warning("Error checking Modrinth updates for GenSprout: " + e.getMessage());
            }
        });
    }

    public boolean isBetaVersion(String version) {
        if (version == null) return false;
        String lower = version.toLowerCase();
        return lower.contains("beta") || lower.contains("alpha") || lower.contains("snapshot") || lower.contains("rc");
    }

    private int compareVersions(String v1, String v2) {
        if (v1.equalsIgnoreCase(v2)) return 0;

        String clean1 = v1.replaceAll("[^0-9.]", " ").trim().split(" ")[0];
        String clean2 = v2.replaceAll("[^0-9.]", " ").trim().split(" ")[0];

        String[] parts1 = clean1.split("\\.");
        String[] parts2 = clean2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int p1 = i < parts1.length && !parts1[i].isEmpty() ? Integer.parseInt(parts1[i]) : 0;
            int p2 = i < parts2.length && !parts2[i].isEmpty() ? Integer.parseInt(parts2[i]) : 0;
            if (p1 != p2) {
                return Integer.compare(p1, p2);
            }
        }

        boolean isBeta1 = isBetaVersion(v1);
        boolean isBeta2 = isBetaVersion(v2);
        if (!isBeta1 && isBeta2) return 1;
        if (isBeta1 && !isBeta2) return -1;

        return v1.compareToIgnoreCase(v2);
    }

    private void sendMessage(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(miniMessage.deserialize(message));
        }
    }

    public String getLatestAvailableVersion() {
        return latestAvailableVersion;
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getModrinthUrl() {
        return modrinthUrl;
    }
}
