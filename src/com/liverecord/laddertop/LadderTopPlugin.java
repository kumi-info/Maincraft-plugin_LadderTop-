package com.liverecord.laddertop;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * s2e-ladder の「頂上（ゴール上空）」へテレポートする補助プラグイン。
 *   /laddertop [高さ] [プレイヤー名|all|random]
 * 初期位置（/ladder tp の場所）の真上へ飛ばす。
 *   頂上の Y = start.y + オフセット
 *   オフセット = （引数省略時）s2e-ladder の ladder.height + extra-height
 */
public final class LadderTopPlugin extends JavaPlugin {

    private static final String LADDER_PLUGIN = "s2e-ladder";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getResource("README.md") != null) {
            saveResource("README.md", true); // plugins/LadderTop/README.md を毎回最新化
        }
        getLogger().info("LadderTop 有効化。/laddertop が利用可能。");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("laddertop")) {
            return false;
        }
        if (!sender.hasPermission("laddertop.use")) {
            sender.sendMessage("§cこのコマンドを使う権限がありません。");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage("§aconfig.yml を再読み込みしました。");
            return true;
        }

        // s2e-ladder の初期位置・ゴール高さを取得。
        Start start = readStart();
        if (start == null) {
            sender.sendMessage("§cs2e-ladder の初期位置を取得できませんでした（config.yml の ladder.start を確認）。");
            return true;
        }
        World world = Bukkit.getWorld(start.world);
        if (world == null) {
            sender.sendMessage("§cワールド '" + start.world + "' が見つかりません。");
            return true;
        }

        // 引数解釈: [高さ] [対象]。先頭が数値なら高さ、非数値なら対象とみなす。
        Integer argHeight = null;
        String targetArg = null;
        if (args.length >= 1) {
            Integer h = tryParseInt(args[0]);
            if (h != null) {
                argHeight = h;
                if (args.length >= 2) {
                    targetArg = args[1];
                }
            } else {
                targetArg = args[0];
            }
        }
        if (argHeight != null && argHeight < 1) {
            sender.sendMessage("§c高さは 1 以上で指定してください。");
            return true;
        }

        int extra = getConfig().getInt("extra-height", 20);
        // 頂上 Y（デフォルト）= ladder.height(1000) + extra-height。start.y は加算しない（絶対 Y）。
        // 高さ引数を指定した場合はその値を絶対 Y として使う。
        int defaultY = start.height + extra;
        double topY = (argHeight != null) ? argHeight : defaultY;

        // ゴール中央へ着地: スタート列の真上で X/Z をブロック中央(floor+0.5)へスナップ。
        // config の微調整オフセットで端数を更にずらせる。
        double centerX = Math.floor(start.x) + 0.5 + getConfig().getDouble("center-x-offset", 0.0);
        double centerZ = Math.floor(start.z) + 0.5 + getConfig().getDouble("center-z-offset", 0.0);

        List<Player> targets = resolveTargets(sender, targetArg);
        if (targets == null) {
            return true; // エラー応答済み。
        }
        if (targets.isEmpty()) {
            sender.sendMessage("§c対象となるプレイヤーが見つかりませんでした。");
            return true;
        }

        String titleMain = getConfig().getString("title.main", "§6§l頂上テレポート");
        int tFadeIn = getConfig().getInt("title.fade-in-ticks", 10);
        int tStay = getConfig().getInt("title.stay-ticks", 50);
        int tFadeOut = getConfig().getInt("title.fade-out-ticks", 20);
        for (Player p : targets) {
            Location loc = new Location(world, centerX, topY, centerZ, start.yaw, start.pitch);
            p.teleport(loc);
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
            // サブタイトルなし（空文字）。
            p.sendTitle(titleMain.replace("%y%", String.valueOf((int) topY)), "", tFadeIn, tStay, tFadeOut);
            p.sendMessage("§b頂上テレポート");
        }

        if (targets.size() == 1) {
            sender.sendMessage("§a" + targets.get(0).getName() + " §6を頂上テレポート。");
        } else {
            sender.sendMessage("§6" + targets.size() + " 人を頂上テレポート。");
        }
        return true;
    }

    /** 第2引数（対象）からテレポート対象リストを決める。null を返した場合はエラー応答済み。 */
    private List<Player> resolveTargets(CommandSender sender, String arg) {
        List<Player> list = new ArrayList<>();
        if (arg == null) {
            if (sender instanceof Player) {
                list.add((Player) sender);
                return list;
            }
            sender.sendMessage("§cコンソールから実行する場合は対象を指定してください。 例: /laddertop 1020 <プレイヤー名|all|random>");
            return null;
        }
        String key = arg.toLowerCase(Locale.ROOT);
        if (key.equals("all") || key.equals("@a")) {
            list.addAll(Bukkit.getOnlinePlayers());
            return list;
        }
        if (key.equals("random") || key.equals("@r")) {
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (online.isEmpty()) {
                return list;
            }
            list.add(online.get(ThreadLocalRandom.current().nextInt(online.size())));
            return list;
        }
        Player p = Bukkit.getPlayerExact(arg);
        if (p == null) {
            sender.sendMessage("§cプレイヤー §e" + arg + " §c が見つかりません（オンラインのみ指定可）。");
            return null;
        }
        list.add(p);
        return list;
    }

    /** s2e-ladder の config.yml から ladder.start と ladder.height を読む。取得不可なら null。 */
    private Start readStart() {
        File file = ladderConfigFile();
        if (!file.exists()) {
            return null;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        if (!cfg.contains("ladder.start.world")) {
            return null;
        }
        Start s = new Start();
        s.world = cfg.getString("ladder.start.world", "world");
        s.x = cfg.getDouble("ladder.start.x");
        s.y = cfg.getDouble("ladder.start.y");
        s.z = cfg.getDouble("ladder.start.z");
        s.yaw = (float) cfg.getDouble("ladder.start.yaw", 0.0);
        s.pitch = (float) cfg.getDouble("ladder.start.pitch", 0.0);
        s.height = cfg.getInt("ladder.height", 1000);
        return s;
    }

    private File ladderConfigFile() {
        Plugin ladder = getServer().getPluginManager().getPlugin(LADDER_PLUGIN);
        File dataFolder = (ladder != null)
                ? ladder.getDataFolder()
                : new File(getDataFolder().getParentFile(), LADDER_PLUGIN);
        return new File(dataFolder, "config.yml");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("laddertop")) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            for (String s : new String[]{"1020", "1100", "500", "reload"}) {
                if (s.startsWith(p)) {
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 2) {
            String p = args[1].toLowerCase(Locale.ROOT);
            List<String> opts = new ArrayList<>();
            opts.add("all");
            opts.add("random");
            for (Player pl : Bukkit.getOnlinePlayers()) {
                opts.add(pl.getName());
            }
            for (String o : opts) {
                if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                    out.add(o);
                }
            }
            return out;
        }
        return out;
    }

    private Integer tryParseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** s2e-ladder の初期位置（/ladder tp の場所）とゴール高さの保持。 */
    private static final class Start {
        String world;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        int height;
    }
}
