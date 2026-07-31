package top.etca.renw;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class renw extends JavaPlugin {

    private QuestManager questManager;

    @Override
    public void onEnable() {
        // 创建配置文件夹
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        // 初始化任务管理器
        this.questManager = new QuestManager(this);
        this.questManager.loadQuests();

        // 注册指令和事件
        getCommand("renw").setExecutor(new RenwCommand(this));
        Bukkit.getPluginManager().registerEvents(new QuestListener(this), this);
        // 注意：传入 this，以便 GuiListener 能访问插件实例
        Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);

        getLogger().info("Renw 任务插件已成功启用！");
    }

    @Override
    public void onDisable() {
        if (questManager != null) {
            questManager.savePlayerData();
        }
        getLogger().info("Renw 任务插件已卸载，数据已保存。");
    }

    public QuestManager getQuestManager() {
        return questManager;
    }
}