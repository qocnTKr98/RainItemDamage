package com.raindamage.plugin;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class RainItemDamage extends JavaPlugin implements Listener {

    private final Map<UUID, Double>  soakLevels  = new HashMap<>();
    private final Map<UUID, Integer> lastWarning = new HashMap<>();
    private boolean    running       = false;
    private BukkitTask rainCheckTask = null;
    private final Random random      = new Random();

    // ──────────────────────────────────────────
    //  플러그인 생명주기
    // ──────────────────────────────────────────
    @Override
    public void onEnable() {
        setupDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("rainchallenge");
        if (cmd != null) {
            RcCommand handler = new RcCommand();
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
        getLogger().info("☔ RainItemDamage 활성화! (1.21.4 / Java 21)");
    }

    @Override
    public void onDisable() {
        if (running) stopGame();
        getLogger().info("☔ RainItemDamage 비활성화.");
    }

    // ──────────────────────────────────────────
    //  기본 config 설정 (config.yml 없어도 기본값 자동 생성)
    // ──────────────────────────────────────────
    private void setupDefaultConfig() {
        getConfig().addDefault("game.force-rain",                   true);
        getConfig().addDefault("item-loss.check-interval",          20);
        getConfig().addDefault("item-loss.soak-increase-per-tick",  1.0);
        getConfig().addDefault("item-loss.max-soak-level",          100.0);
        getConfig().addDefault("item-loss.soak-threshold",          10.0);
        getConfig().addDefault("item-loss.max-loss-chance",         0.15);
        getConfig().addDefault("item-loss.dry-rate",                2.0);
        getConfig().addDefault("item-loss.hotbar-only",             false);
        getConfig().addDefault("messages.prefix",                   "&b[☔ 레인 챌린지] &r");
        getConfig().addDefault("messages.game-start",               "&a챌린지가 시작되었습니다! 비를 피하세요!");
        getConfig().addDefault("messages.game-stop",                "&c챌린지가 종료되었습니다.");
        getConfig().addDefault("messages.soak-warning-50",          "&e⚠ 절반 이상 젖었습니다!");
        getConfig().addDefault("messages.soak-warning-80",          "&c⚠ 심각하게 젖었습니다!");
        getConfig().addDefault("messages.soak-warning-max",         "&4☔ 완전히 젖었습니다!");
        getConfig().addDefault("debug",                             false);
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    // ──────────────────────────────────────────
    //  게임 제어
    // ──────────────────────────────────────────
    private boolean startGame(Player starter) {
        if (running) return false;
        running = true;

        for (Player p : Bukkit.getOnlinePlayers()) initPlayer(p);

        if (getConfig().getBoolean("game.force-rain", true)) setRain(true);

        int interval = getConfig().getInt("item-loss.check-interval", 20);
        rainCheckTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running) { cancel(); return; }
                // 리스트 복사 후 순회 (ConcurrentModification 방지)
                for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                    if (p.isOnline()) tickPlayer(p);
                }
            }
        }.runTaskTimer(this, 0L, interval);

        broadcast("messages.game-start", "&a챌린지가 시작되었습니다! 비를 피하세요!");
        getLogger().info("☔ 챌린지 시작 — " + (starter != null ? starter.getName() : "콘솔"));
        return true;
    }

    private boolean stopGame() {
        if (!running) return false;
        running = false;

        if (rainCheckTask != null) {
            rainCheckTask.cancel();
            rainCheckTask = null;
        }

        setRain(false);
        soakLevels.clear();
        lastWarning.clear();

        broadcast("messages.game-stop", "&c챌린지가 종료되었습니다.");
        getLogger().info("☔ 챌린지 종료");
        return true;
    }

    // ──────────────────────────────────────────
    //  매 틱 처리
    // ──────────────────────────────────────────
    private void tickPlayer(Player player) {
        if (!player.isOnline()) return;
        if (player.hasPermission("rainchallenge.bypass")) return;
        if (player.getGameMode() == GameMode.SPECTATOR) return;

        if (isInRain(player)) {
            increaseSoak(player);
            spawnRainParticle(player);
            if (shouldLoseItem(player)) removeRandomItem(player);

            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("[DEBUG] " + player.getName()
                    + " 적심: " + String.format("%.1f", getSoakPercent(player)) + "%");
            }
        } else {
            decreaseSoak(player);
        }

        showSoakStatus(player);
    }

    // ──────────────────────────────────────────
    //  비 감지
    // ──────────────────────────────────────────
    private boolean isInRain(Player player) {
        World world = player.getWorld();

        // 월드에 비가 내리고 있는지
        if (!world.hasStorm()) return false;

        Location loc = player.getLocation();

        // 플레이어 머리 위에 지붕이 있는지 (하늘이 안 보이면 실내)
        int highestY = world.getHighestBlockYAt(loc);
        if (loc.getBlockY() < highestY) return false;

        // 비가 내리지 않는 바이옴 제외
        String biome = loc.getBlock().getBiome().name();
        if (biome.contains("DESERT")   ||
            biome.contains("BADLANDS") ||
            biome.contains("SAVANNA")  ||
            biome.contains("NETHER")   ||
            biome.contains("END")) {
            return false;
        }

        return true;
    }

    // ──────────────────────────────────────────
    //  적심 수치
    // ──────────────────────────────────────────
    private void initPlayer(Player player) {
        soakLevels.put(player.getUniqueId(), 0.0);
        lastWarning.put(player.getUniqueId(), 0);
    }

    private double getSoakLevel(Player player) {
        return soakLevels.getOrDefault(player.getUniqueId(), 0.0);
    }

    private double getSoakPercent(Player player) {
        double max = getConfig().getDouble("item-loss.max-soak-level", 100);
        return (getSoakLevel(player) / max) * 100.0;
    }

    private void increaseSoak(Player player) {
        double max      = getConfig().getDouble("item-loss.max-soak-level", 100);
        double inc      = getConfig().getDouble("item-loss.soak-increase-per-tick", 1);
        double newLevel = Math.min(getSoakLevel(player) + inc, max);
        soakLevels.put(player.getUniqueId(), newLevel);

        // 경고 메시지 (한 번만 발송)
        double percent = (newLevel / max) * 100;
        int level = percent >= 100 ? 3 : percent >= 80 ? 2 : percent >= 50 ? 1 : 0;
        int prev  = lastWarning.getOrDefault(player.getUniqueId(), 0);
        if (level > prev) {
            lastWarning.put(player.getUniqueId(), level);
            String key = level == 3 ? "messages.soak-warning-max"
                       : level == 2 ? "messages.soak-warning-80"
                       :              "messages.soak-warning-50";
            player.sendMessage(colorize(prefix + getConfig().getString(key, "")));
        }
    }

    private void decreaseSoak(Player player) {
        double dry      = getConfig().getDouble("item-loss.dry-rate", 2);
        double max      = getConfig().getDouble("item-loss.max-soak-level", 100);
        double newLevel = Math.max(getSoakLevel(player) - dry, 0);
        soakLevels.put(player.getUniqueId(), newLevel);

        // 경고 레벨 내려가면 다시 발송 가능하도록 리셋
        double percent = (newLevel / max) * 100;
        int level = percent >= 100 ? 3 : percent >= 80 ? 2 : percent >= 50 ? 1 : 0;
        if (level < lastWarning.getOrDefault(player.getUniqueId(), 0)) {
            lastWarning.put(player.getUniqueId(), level);
        }
    }

    private boolean shouldLoseItem(Player player) {
        double soakLevel = getSoakLevel(player);
        double threshold = getConfig().getDouble("item-loss.soak-threshold", 10);
        if (soakLevel < threshold) return false;
        double max       = getConfig().getDouble("item-loss.max-soak-level", 100);
        double maxChance = getConfig().getDouble("item-loss.max-loss-chance", 0.15);
        return random.nextDouble() < (soakLevel / max) * maxChance;
    }

    // ──────────────────────────────────────────
    //  아이템 제거 — 갑옷 슬롯(36~39) 절대 제외
    // ──────────────────────────────────────────
    private void removeRandomItem(Player player) {
        PlayerInventory inv    = player.getInventory();
        boolean hotbarOnly     = getConfig().getBoolean("item-loss.hotbar-only", false);
        List<Integer> slots    = new ArrayList<>();

        // 메인 인벤토리 (핫바 포함, 갑옷 슬롯 미포함)
        int limit = hotbarOnly ? 9 : 36;
        for (int i = 0; i < limit; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) slots.add(i);
        }

        // 오프핸드 (핫바 전용이 아닐 때만)
        if (!hotbarOnly && inv.getItemInOffHand().getType() != Material.AIR) {
            slots.add(40); // 40 = 오프핸드 마커
        }

        if (slots.isEmpty()) return;

        int slot = slots.get(random.nextInt(slots.size()));
        ItemStack removed;

        if (slot == 40) {
            removed = inv.getItemInOffHand().clone();
            inv.setItemInOffHand(new ItemStack(Material.AIR));
        } else {
            removed = inv.getItem(slot);
            inv.setItem(slot, null);
        }

        if (removed == null || removed.getType() == Material.AIR) return;

        // 파티클 — SPLASH (1.21.4 공식 확인된 이름)
        player.getWorld().spawnParticle(
            Particle.SPLASH,
            player.getLocation().add(0, 1, 0),
            20, 0.4, 0.6, 0.4, 0.05
        );

        // 소리 — ENTITY_GENERIC_SPLASH (1.21.4 공식 확인)
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_SPLASH, 0.6f, 1.4f);


        getLogger().info("[RainChallenge] " + player.getName()
            + " 아이템 소실: " + removed.getType().name() + " (슬롯 " + slot + ")");
    }

    // ──────────────────────────────────────────
    //  이펙트 & UI
    // ──────────────────────────────────────────
    private void spawnRainParticle(Player player) {
        if (getSoakPercent(player) > 30) {
            // DRIPPING_WATER — 1.21.4 공식 확인된 이름
            player.getWorld().spawnParticle(
                Particle.DRIPPING_WATER,
                player.getLocation().add(0, 2.1, 0),
                4, 0.3, 0.05, 0.3, 0
            );
        }
    }

    private void showSoakStatus(Player player) {
        double percent = getSoakPercent(player);
        int filled     = (int)(percent / 5); // 20칸 게이지

        String color = percent > 80 ? "§c" : percent > 50 ? "§e" : "§b";
        StringBuilder bar = new StringBuilder(color);
        for (int i = 0; i < 20; i++) {
            bar.append(i < filled ? "█" : "§7░");
        }

        String status;
        if      (percent >= 100) status = "§4완전히 젖음!";
        else if (percent >= 80)  status = "§c심각하게 젖음";
        else if (percent >= 50)  status = "§e절반 젖음";
        else if (percent >= 10)  status = "§b젖는 중";
        else                     status = "§a건조";

        String text = colorize("☔ 적심: " + bar + " §f"
            + String.format("%.0f", percent) + "% §7(" + status + "§7)");

        // BungeeCord API — 1.21.4 안전하게 호환
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent.fromLegacyText(text)
        );
    }

    // ──────────────────────────────────────────
    //  날씨 제어
    // ──────────────────────────────────────────
    private void setRain(boolean rain) {
        for (World world : Bukkit.getWorlds()) {
            if (rain) {
                world.setStorm(true);
                world.setThundering(false);
                world.setWeatherDuration(Integer.MAX_VALUE);
                world.setStormDuration(Integer.MAX_VALUE);
                world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            } else {
                world.setStorm(false);
                world.setWeatherDuration(0);
                world.setGameRule(GameRule.DO_WEATHER_CYCLE, true);
            }
        }
    }

    // ──────────────────────────────────────────
    //  이벤트 리스너
    // ──────────────────────────────────────────
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!running) return;
        initPlayer(e.getPlayer());
        e.getPlayer().sendMessage(colorize(prefix + "&e현재 레인 챌린지 진행 중! 비를 피하세요!"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        soakLevels.remove(e.getPlayer().getUniqueId());
        lastWarning.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWeather(WeatherChangeEvent e) {
        if (!running) return;
        if (!getConfig().getBoolean("game.force-rain", true)) return;
        if (!e.toWeatherState()) e.setCancelled(true);
    }

    // ──────────────────────────────────────────
    //  유틸
    // ──────────────────────────────────────────
    private void broadcast(String key, String fallback) {
        Bukkit.broadcastMessage(colorize(prefix + getConfig().getString(key, fallback)));
    }

    private String colorize(String msg) {
        return msg.replace("&", "§");
    }

    // ──────────────────────────────────────────
    //  /rainchallenge 커맨드
    // ──────────────────────────────────────────
    private class RcCommand implements CommandExecutor, TabCompleter {

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!sender.hasPermission("rainchallenge.admin")) {
                sender.sendMessage(colorize("&c권한이 없습니다."));
                return true;
            }


            if (args.length == 0) {
                sendHelp(sender, label);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "start" -> {
                    Player p = sender instanceof Player pl ? pl : null;
                    sender.sendMessage(colorize(prefix +
                        (startGame(p) ? "&a챌린지 시작!" : "&c이미 진행 중입니다.")));
                }
                case "stop" -> sender.sendMessage(colorize(prefix +
                    (stopGame() ? "&c챌린지 종료." : "&c진행 중인 챌린지가 없습니다.")));
                case "status" -> {
                    sender.sendMessage(colorize(prefix + "상태: "
                        + (running ? "&a진행 중 ☔" : "&7대기 중")));
                    if (running) {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            sender.sendMessage(colorize("  &b" + p.getName()
                                + " &f" + String.format("%.0f", getSoakPercent(p)) + "%"));
                        }
                    }
                }
                case "reload" -> {
                    reloadConfig();
                    setupDefaultConfig();
                    sender.sendMessage(colorize(prefix + "&a설정 리로드 완료."));
                }
                default -> sendHelp(sender, label);
            }
            return true;
        }

        private void sendHelp(CommandSender s, String l) {
            s.sendMessage(colorize("&7/" + l + " &fstart &7| &fstop &7| &fstatus &7| &freload"));
        }

        @Override
        public List<String> onTabComplete(CommandSender s, Command c, String a, String[] args) {
            return args.length == 1 ? List.of("start", "stop", "status", "reload") : null;
        }
    }
}
