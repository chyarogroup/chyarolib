package hr.chyaro.lib.api;

public interface GenSproutAddon {
    default void onLoad(AddonContext context) throws Exception { }
    void onEnable() throws Exception;
    default void onReload() throws Exception { }
    default void onDisable() throws Exception { }
}
