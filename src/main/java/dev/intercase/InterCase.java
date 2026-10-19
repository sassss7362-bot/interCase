package dev.intercase;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class InterCase extends JavaPlugin implements Listener {

    // ───────────────────────── моделі ─────────────────────────

    enum Rarity {
        COMMON("Звичайний", 0xAAAAAA, Material.WOODEN_SWORD),
        UNCOMMON("Незвичайний", 0x55FF55, Material.STONE_SWORD),
        RARE("Рідкісний", 0x55AAFF, Material.IRON_SWORD),
        EPIC("Епічний", 0xCC55FF, Material.GOLDEN_SWORD),
        LEGENDARY("Легендарний", 0xFFAA00, Material.DIAMOND_SWORD),
        MYTHIC("Міфічний", 0xFF5555, Material.NETHERITE_SWORD);

        final String label; final int rgb; final Material sword;
        Rarity(String l, int rgb, Material s) { this.label = l; this.rgb = rgb; this.sword = s; }
        TextColor tc() { return TextColor.color(rgb); }
        Color bc() { return Color.fromRGB(rgb); }

        static Rarity of(double p) {
            if (p >= 25) return COMMON;
            if (p >= 12) return UNCOMMON;
            if (p >= 6) return RARE;
            if (p >= 3.5) return EPIC;
            if (p >= 1) return LEGENDARY;
            return MYTHIC;
        }
    }

    static class Reward {
        String name; Material icon; double weight, pct; Rarity rarity;
        List<String> commands = new ArrayList<>();
    }

    static class CaseDef {
        String id, name; double total;
        List<Reward> rewards = new ArrayList<>();
    }

    static class PreviewHolder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    static final double PD = Math.toRadians(225); // меч вістрям вниз
    static final EntityType[] MOBS = {
            EntityType.WITHER_SKELETON, EntityType.IRON_GOLEM, EntityType.CREEPER, EntityType.VILLAGER,
            EntityType.ENDERMAN, EntityType.WITCH, EntityType.SPIDER, EntityType.HUSK, EntityType.STRAY,
            EntityType.RAVAGER, EntityType.EVOKER, EntityType.PILLAGER, EntityType.VINDICATOR};

    final Map<String, CaseDef> cases = new LinkedHashMap<>();
    final Map<String, String> blockToCase = new HashMap<>();
    final Map<String, TextDisplay> holos = new HashMap<>();
    final Map<UUID, Show> shows = new HashMap<>();
    final Set<String> busy = new HashSet<>();
    final Random rnd = new Random();
    NamespacedKey kKey;
    File dataFile;

    // ───────────────────────── життєвий цикл ─────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();
        kKey = new NamespacedKey(this, "case_key");
        dataFile = new File(getDataFolder(), "data.yml");
        loadCases();
        loadData();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::ensureHolos, 20L, 100L);
    }

    @Override
    public void onDisable() {
        for (Show s : new ArrayList<>(shows.values())) s.forceFinish(true);
        for (TextDisplay h : holos.values()) if (h.isValid()) h.remove();
        holos.clear();
    }

    // ───────────────────────── утиліти ─────────────────────────

    Component legacy(String s) { return LegacyComponentSerializer.legacyAmpersand().deserialize(s); }
    Component noItalic(Component c) { return c.decoration(TextDecoration.ITALIC, false); }
    String fmt(double v) { return String.format(Locale.US, v >= 10 ? "%.0f" : "%.1f", v); }
    String bkey(Location l) { return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ(); }

    Location fromKey(String k) {
        String[] a = k.split(";");
        World w = Bukkit.getWorld(a[0]);
        if (w == null) return null;
        return new Location(w, Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
    }

    Component labelComp(Reward r) {
        return legacy(r.name).append(Component.newline())
                .append(Component.text("Шанс: " + fmt(r.pct) + "%", r.rarity.tc(), TextDecoration.BOLD));
    }

    TextDisplay mkText(Location l, Component c, float scale) {
        TextDisplay td = l.getWorld().spawn(l, TextDisplay.class);
        td.text(c);
        td.setBillboard(Display.Billboard.CENTER);
        td.setShadowed(true);
        td.setBackgroundColor(Color.fromARGB(70, 0, 0, 0));
        td.setPersistent(false);
        td.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        return td;
    }

    ItemDisplay mkItem(Location l, ItemStack is, float scale, Display.Billboard bb) {
        ItemDisplay id = l.getWorld().spawn(l, ItemDisplay.class);
        id.setItemStack(is);
        id.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        id.setBillboard(bb);
        id.setPersistent(false);
        tf(id, scale, 0);
        return id;
    }

    void tf(Display d, float s, double angZ) {
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateZ((float) angZ),
                new Vector3f(s, s, s), new Quaternionf()));
    }

    // ───────────────────────── дані ─────────────────────────

    void loadCases() {
        reloadConfig();
        cases.clear();
        ConfigurationSection cs = getConfig().getConfigurationSection("cases");
        if (cs == null) return;
        for (String id : cs.getKeys(false)) {
            ConfigurationSection c = cs.getConfigurationSection(id);
            if (c == null) continue;
            CaseDef d = new CaseDef();
            d.id = id;
            d.name = c.getString("name", id);
            for (Map<?, ?> m : c.getMapList("rewards")) {
                Reward r = new Reward();
                r.name = String.valueOf(m.get("name"));
                Material mat = Material.matchMaterial(String.valueOf(m.get("icon")));
                r.icon = mat != null ? mat : Material.CHEST;
                Object ch = m.get("chance");
                r.weight = ch instanceof Number n ? n.doubleValue() : 1.0;
                Object co = m.get("commands");
                if (co instanceof List<?> l) for (Object o : l) r.commands.add(String.valueOf(o));
                d.rewards.add(r);
                d.total += r.weight;
            }
            if (d.rewards.size() != 8) {
                getLogger().warning("Кейс " + id + ": потрібно рівно 8 нагород, а є " + d.rewards.size() + ". Пропущено.");
                continue;
            }
            for (Reward r : d.rewards) { r.pct = r.weight / d.total * 100.0; r.rarity = Rarity.of(r.pct); }
            d.rewards.sort((a, b) -> Double.compare(b.weight, a.weight));
            cases.put(id, d);
        }
    }

    void loadData() {
        blockToCase.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String s : y.getStringList("blocks")) {
            String[] a = s.split("\\|", 2);
            if (a.length == 2) blockToCase.put(a[1], a[0]);
        }
    }

    void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, String> e : blockToCase.entrySet()) l.add(e.getValue() + "|" + e.getKey());
        y.set("blocks", l);
        try { y.save(dataFile); } catch (IOException ex) { getLogger().warning(ex.getMessage()); }
    }

    // ───────────────────────── голограми над кейсами ─────────────────────────

    void ensureHolos() {
        for (Map.Entry<String, String> e : blockToCase.entrySet()) {
            String k = e.getKey();
            if (busy.contains(k)) continue;
            TextDisplay h = holos.get(k);
            if (h != null && h.isValid()) continue;
            Location l = fromKey(k);
            CaseDef d = cases.get(e.getValue());
            if (l == null || d == null) continue;
            if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            Component c = legacy("&6&l✦ InterTime ✦").append(Component.newline())
                    .append(legacy(d.name)).append(Component.newline())
                    .append(legacy("&7ПКМ з ключем — відкрити")).append(Component.newline())
                    .append(legacy("&8ЛКМ — що всередині"));
            holos.put(k, mkText(l.clone().add(0.5, 2.1, 0.5), c, 1.5f));
        }
    }

    void dropHolo(String k) {
        TextDisplay h = holos.remove(k);
        if (h != null && h.isValid()) h.remove();
    }

    // ───────────────────────── ключі ─────────────────────────

    ItemStack makeKey(CaseDef d, int n) {
        ItemStack it = new ItemStack(Material.TRIPWIRE_HOOK, n);
        ItemMeta m = it.getItemMeta();
        m.displayName(noItalic(legacy("&f&lКлюч &8» ").append(legacy(d.name))));
        m.lore(List.of(
                noItalic(legacy("&7Ключ від кейса сервера &6InterTime")),
                noItalic(legacy("&7Натисни ПКМ по кейсу, щоб відкрити"))));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(kKey, PersistentDataType.STRING, d.id);
        it.setItemMeta(m);
        return it;
    }

    String keyId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(kKey, PersistentDataType.STRING);
    }

    void giveKey(Player p, CaseDef d, int n) {
        for (ItemStack left : p.getInventory().addItem(makeKey(d, n)).values())
            p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    // ───────────────────────── команди ─────────────────────────

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("intercase.admin")) { s.sendMessage(legacy("&cНемає прав.")); return true; }
        if (a.length == 0) { help(s); return true; }
        switch (a[0].toLowerCase()) {
            case "key" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Тільки для гравців."); return true; }
                CaseDef d = need(s, a, 1);
                if (d == null) return true;
                int n = amount(a, 2);
                giveKey(p, d, n);
                p.sendMessage(legacy("&aТи отримав ключ x" + n + ": ").append(legacy(d.name)));
            }
            case "give" -> {
                if (a.length < 3) { help(s); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage(legacy("&cГравця не знайдено.")); return true; }
                CaseDef d = need(s, a, 2);
                if (d == null) return true;
                int n = amount(a, 3);
                giveKey(t, d, n);
                s.sendMessage(legacy("&aВидано " + t.getName() + " ключ x" + n + ": ").append(legacy(d.name)));
                t.sendMessage(legacy("&6&l✦ InterTime &8» &fТи отримав ключ x" + n + ": ").append(legacy(d.name)));
            }
            case "set" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Тільки для гравців."); return true; }
                CaseDef d = need(s, a, 1);
                if (d == null) return true;
                Block b = p.getTargetBlockExact(6);
                if (b == null) { p.sendMessage(legacy("&cДивись на блок (скриня, бочка...) ближче 6 блоків.")); return true; }
                String k = bkey(b.getLocation());
                dropHolo(k);
                blockToCase.put(k, d.id);
                saveData();
                ensureHolos();
                p.sendMessage(legacy("&aБлок стає кейсом: ").append(legacy(d.name)));
            }
            case "remove" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Тільки для гравців."); return true; }
                Block b = p.getTargetBlockExact(6);
                if (b == null) { p.sendMessage(legacy("&cДивись на блок кейса.")); return true; }
                String k = bkey(b.getLocation());
                if (blockToCase.remove(k) == null) { p.sendMessage(legacy("&cЦе не кейс.")); return true; }
                dropHolo(k);
                saveData();
                p.sendMessage(legacy("&aКейс видалено."));
            }
            case "list" -> {
                s.sendMessage(legacy("&6&l✦ InterTime — кейси:"));
                for (CaseDef d : cases.values()) s.sendMessage(legacy("&7" + d.id + " &8» ").append(legacy(d.name)));
            }
            case "reload" -> {
                loadCases();
                loadData();
                for (String k : new ArrayList<>(holos.keySet())) dropHolo(k);
                ensureHolos();
                s.sendMessage(legacy("&aПерезавантажено. Кейсів: " + cases.size()));
            }
            default -> help(s);
        }
        return true;
    }

    void help(CommandSender s) {
        s.sendMessage(legacy("&6&l✦ InterCase &8» &7команди:"));
        s.sendMessage(legacy("&e/case key <1-11> [к-сть] &7— ключ собі в руки"));
        s.sendMessage(legacy("&e/case give <гравець> <1-11> [к-сть] &7— видати ключ гравцю"));
        s.sendMessage(legacy("&e/case set <1-11> &7— зробити блок, на який дивишся, кейсом"));
        s.sendMessage(legacy("&e/case remove &7— прибрати кейс (блок, на який дивишся)"));
        s.sendMessage(legacy("&e/case list &7| &e/case reload"));
    }

    CaseDef need(CommandSender s, String[] a, int i) {
        if (a.length <= i) { s.sendMessage(legacy("&cВкажи номер кейса (1-" + cases.size() + ").")); return null; }
        CaseDef d = cases.get(a[i]);
        if (d == null) s.sendMessage(legacy("&cТакого кейса немає. /case list"));
        return d;
    }

    int amount(String[] a, int i) {
        if (a.length <= i) return 1;
        try { return Math.max(1, Math.min(64, Integer.parseInt(a[i]))); } catch (NumberFormatException e) { return 1; }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> r = new ArrayList<>();
        if (!s.hasPermission("intercase.admin")) return r;
        if (a.length == 1) {
            for (String x : List.of("key", "give", "set", "remove", "list", "reload")) if (x.startsWith(a[0].toLowerCase())) r.add(x);
        } else if (a.length == 2 && (a[0].equalsIgnoreCase("key") || a[0].equalsIgnoreCase("set"))) {
            r.addAll(cases.keySet());
        } else if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) r.add(p.getName());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("give")) {
            r.addAll(cases.keySet());
        }
        return r;
    }

    // ───────────────────────── події ─────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_BLOCK) return;
        String cid = blockToCase.get(bkey(b.getLocation()));
        if (cid == null) return;
        e.setCancelled(true);
        CaseDef d = cases.get(cid);
        if (d == null) return;
        Player p = e.getPlayer();
        if (a == Action.RIGHT_CLICK_BLOCK) {
            String kid = keyId(p.getInventory().getItemInMainHand());
            if (kid != null && kid.equals(cid)) { startShow(p, d, b); return; }
            if (kid != null) {
                p.sendMessage(legacy("&cЦе ключ від іншого кейса. Потрібен: ").append(legacy(d.name)));
                return;
            }
            p.sendMessage(legacy("&6&l✦ InterTime &8» &7Потрібен ключ: ").append(legacy(d.name)));
        }
        preview(p, d);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (blockToCase.containsKey(bkey(e.getBlock().getLocation()))) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(legacy("&cЦе кейс. Прибрати: /case remove"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Show s = shows.get(e.getPlayer().getUniqueId());
        if (s != null) s.forceFinish(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PreviewHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof PreviewHolder) e.setCancelled(true);
    }

    // ───────────────────────── перегляд нагород ─────────────────────────

    void preview(Player p, CaseDef d) {
        PreviewHolder h = new PreviewHolder();
        Inventory inv = Bukkit.createInventory(h, 27, legacy("&6&l✦ InterTime &8» ").append(legacy(d.name)));
        h.inv = inv;
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta();
        pm.displayName(Component.text(" "));
        pane.setItemMeta(pm);
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        for (int i = 0; i < 8; i++) {
            Reward r = d.rewards.get(i);
            ItemStack it = new ItemStack(r.icon);
            ItemMeta m = it.getItemMeta();
            m.displayName(noItalic(legacy(r.name)));
            m.lore(List.of(noItalic(Component.text("Шанс: ", NamedTextColor.GRAY)
                    .append(Component.text(fmt(r.pct) + "%", r.rarity.tc(), TextDecoration.BOLD)))));
            it.setItemMeta(m);
            inv.setItem(9 + i, it);
        }
        p.openInventory(inv);
    }

    // ───────────────────────── запуск анімації ─────────────────────────

    void startShow(Player p, CaseDef d, Block b) {
        String k = bkey(b.getLocation());
        if (busy.contains(k)) { p.sendMessage(legacy("&cЦей кейс зараз відкривається.")); return; }
        if (shows.containsKey(p.getUniqueId())) { p.sendMessage(legacy("&cТи вже відкриваєш кейс.")); return; }
        ItemStack hand = p.getInventory().getItemInMainHand().clone();
        hand.setAmount(hand.getAmount() - 1);
        p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
        int anim = getConfig().getInt("force-animation", -1);
        if (anim < 0 || anim > 3) anim = rnd.nextInt(4);
        busy.add(k);
        dropHolo(k);
        Show s = new Show(p, d, b, anim);
        shows.put(p.getUniqueId(), s);
        s.runTaskTimer(this, 0L, 1L);
    }

    // ───────────────────────── саме шоу ─────────────────────────

    class Show extends BukkitRunnable {
        final Player p; final CaseDef d; final Location base; final String bkey; final int anim;
        final Reward[] slot = new Reward[8];
        final Entity[] main = new Entity[8];
        final TextDisplay[] lab = new TextDisplay[8];
        final boolean[] out = new boolean[8];
        final List<Entity> all = new ArrayList<>();
        final List<Integer> elim = new ArrayList<>();
        final double R = getConfig().getDouble("ring-radius", 3.6);
        final Reward prize;
        int win, t = 0, endAt = -1, revT = 0, fwLeft = 0;
        boolean revealed, finished, rewardGiven;
        // для різних анімацій
        final List<EntityType> mobTypes = new ArrayList<>();
        double theta = 0;
        final Vector[] fv = new Vector[8];
        final int[] fEnd = new int[8];
        int cursor, steps, kStep, nextAt = -1, doneT = -1;

        Show(Player p, CaseDef d, Block b, int anim) {
            this.p = p; this.d = d; this.anim = anim;
            this.base = b.getLocation().add(0.5, 0, 0.5);
            this.bkey = InterCase.this.bkey(b.getLocation());
            double r = rnd.nextDouble() * d.total, acc = 0;
            Reward w = d.rewards.get(7);
            for (Reward x : d.rewards) { acc += x.weight; if (r < acc) { w = x; break; } }
            prize = w;
            List<Reward> sh = new ArrayList<>(d.rewards);
            Collections.shuffle(sh, rnd);
            for (int i = 0; i < 8; i++) { slot[i] = sh.get(i); if (slot[i] == w) win = i; }
            for (int i = 0; i < 8; i++) if (i != win) elim.add(i);
            Collections.shuffle(elim, rnd);
            List<EntityType> mt = new ArrayList<>(List.of(MOBS));
            Collections.shuffle(mt, rnd);
            for (int i = 0; i < 8; i++) mobTypes.add(mt.get(i));
        }

        // ── допоміжне ──
        Location pos(int i, double y) {
            double a = i * Math.PI * 2 / 8;
            return base.clone().add(R * Math.cos(a), y, R * Math.sin(a));
        }
        void snd(Sound s, float v, float pitch) { base.getWorld().playSound(base, s, v, pitch); }
        void fx(Particle pt, Location l, int n, double speed) { base.getWorld().spawnParticle(pt, l, n, 0.3, 0.3, 0.3, speed); }
        <T extends Entity> T track(T e) { all.add(e); return e; }
        TextDisplay text(Location l, Component c, float sc) { return track(mkText(l, c, sc)); }
        ItemDisplay item(Location l, ItemStack is, float sc, Display.Billboard bb) { return track(mkItem(l, is, sc, bb)); }

        void rm(int i) {
            if (main[i] != null) { main[i].remove(); main[i] = null; }
            if (lab[i] != null) { lab[i].remove(); lab[i] = null; }
        }

        void glow(int i, boolean on) {
            if (main[i] == null) return;
            main[i].setGlowing(on);
            if (main[i] instanceof Display dsp) dsp.setGlowColorOverride(slot[i].rarity.bc());
        }

        void firework(Location l, Color c) {
            Firework f = l.getWorld().spawn(l, Firework.class);
            FireworkMeta m = f.getFireworkMeta();
            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(c)
                    .withFade(Color.WHITE).flicker(true).trail(true).build());
            m.setPower(0);
            f.setFireworkMeta(m);
            f.detonate();
        }

        // ── головний цикл ──
        @Override
        public void run() {
            if (!p.isOnline()) { forceFinish(true); return; }
            if (t == 0) intro();
            switch (anim) {
                case 0 -> swords();
                case 1 -> mobs();
                case 2 -> vortex();
                default -> roulette();
            }
            if (revealed && fwLeft > 0 && (t - revT) % 8 == 0) {
                firework(base.clone().add(rnd.nextDouble() * 4 - 2, 2.5, rnd.nextDouble() * 4 - 2), prize.rarity.bc());
                fwLeft--;
            }
            if (endAt >= 0 && t >= endAt) { finish(); return; }
            t++;
        }

        void intro() {
            snd(Sound.BLOCK_ENDER_CHEST_OPEN, 2f, 0.8f);
            p.showTitle(Title.title(legacy("&6&l✦ InterTime ✦"), legacy(d.name),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(500))));
            Component c = legacy("&6&l✦ InterTime ✦").append(Component.newline()).append(legacy(d.name));
            text(base.clone().add(0, 3.2, 0), c, 2.6f);
        }

        // ── ефекти вибування ──
        void eliminate(int i) {
            out[i] = true;
            Location l = main[i] != null ? main[i].getLocation() : pos(i, 1);
            switch (anim) {
                case 0 -> {
                    snd(Sound.ITEM_SHIELD_BREAK, 1.5f, 1f + rnd.nextFloat() * 0.3f);
                    fx(Particle.CRIT, l, 30, 0.3);
                    fx(Particle.POOF, l, 10, 0.05);
                }
                case 1 -> {
                    base.getWorld().strikeLightningEffect(l);
                    fx(Particle.EXPLOSION, l.clone().add(0, 1, 0), 1, 0);
                    fx(Particle.POOF, l.clone().add(0, 1, 0), 25, 0.1);
                }
                default -> {
                    snd(Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 1.2f);
                    fx(Particle.POOF, l, 15, 0.1);
                }
            }
            rm(i);
        }

        // ── 0: МЕЧІ ──
        void swords() {
            final int L0 = 15, GAP = 6, FL = 22;
            Location S = base.clone().add(0, 1.3, 0);
            for (int i = 0; i < 8; i++) {
                int ls = L0 + i * GAP;
                if (t == ls) {
                    ItemStack sw = new ItemStack(slot[i].rarity.sword);
                    if (slot[i].rarity == Rarity.MYTHIC) {
                        ItemMeta m = sw.getItemMeta();
                        m.setEnchantmentGlintOverride(true);
                        sw.setItemMeta(m);
                    }
                    main[i] = item(S, sw, 1.6f, Display.Billboard.VERTICAL);
                    snd(Sound.ITEM_TRIDENT_THROW, 1.5f, 0.9f + i * 0.05f);
                }
                if (t > ls && t <= ls + FL && main[i] != null && !out[i]) {
                    double f = (double) (t - ls) / FL;
                    Location T = pos(i, 0.65);
                    Location cur = S.clone().add((T.getX() - S.getX()) * f,
                            (T.getY() - S.getY()) * f + 3.4 * Math.sin(Math.PI * f),
                            (T.getZ() - S.getZ()) * f);
                    main[i].teleport(cur);
                    tf((Display) main[i], 1.6f, PD - (1 - f) * 4 * Math.PI);
                    fx(Particle.CRIT, cur, 3, 0.02);
                    if (t == ls + FL) {
                        snd(Sound.ITEM_TRIDENT_HIT_GROUND, 1.5f, 0.8f + i * 0.04f);
                        fx(Particle.POOF, pos(i, 0.15), 12, 0.05);
                        fx(Particle.CLOUD, pos(i, 0.15), 6, 0.02);
                        lab[i] = text(pos(i, 2.7), labelComp(slot[i]), 1.0f);
                    }
                }
            }
            int es = L0 + 7 * GAP + FL + 22;
            for (int k = 0; k < 7; k++) {
                int et = es + k * 10 + (k == 6 ? 15 : 0);
                if (t == et) eliminate(elim.get(k));
            }
            int rise = es + 6 * 10 + 15 + 25;
            if (t == rise) { glow(win, true); snd(Sound.BLOCK_BEACON_ACTIVATE, 2f, 1.5f); }
            if (t >= rise && t <= rise + 30 && main[win] != null) {
                double f = (t - rise) / 30.0;
                Location w = pos(win, 0.65 + 1.9 * f);
                main[win].teleport(w);
                tf((Display) main[win], (float) (1.6 + 1.4 * f), PD);
                if (lab[win] != null) lab[win].teleport(w.clone().add(0, 2.0, 0));
                fx(Particle.END_ROD, w, 4, 0.02);
            }
            if (t == rise + 30) reveal();
        }

        // ── 1: МОБИ ──
        void mobs() {
            for (int i = 0; i < 8; i++) {
                if (t == 10 + i * 9) {
                    Location l = pos(i, 0);
                    l.setDirection(base.toVector().subtract(l.toVector()));
                    Entity e = l.getWorld().spawnEntity(l, mobTypes.get(i));
                    track(e);
                    if (e instanceof LivingEntity le) {
                        le.setAI(false);
                        le.setSilent(true);
                        le.setInvulnerable(true);
                        le.setPersistent(false);
                        le.setRemoveWhenFarAway(true);
                        le.setCollidable(false);
                    }
                    main[i] = e;
                    lab[i] = text(l.clone().add(0, e.getHeight() + 0.75, 0), labelComp(slot[i]), 1.0f);
                    snd(Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, 1f);
                    fx(Particle.POOF, l.clone().add(0, 1, 0), 25, 0.08);
                }
            }
            int es = 10 + 7 * 9 + 27;
            for (int k = 0; k < 7; k++) {
                int et = es + k * 10 + (k == 6 ? 20 : 0);
                if (t == et) eliminate(elim.get(k));
            }
            int jump = es + 6 * 10 + 20 + 25;
            if (t == jump && main[win] != null) {
                main[win].setGlowing(true);
                main[win].setVelocity(new Vector(0, 0.8, 0));
                snd(Sound.ENTITY_ENDERMAN_TELEPORT, 2f, 0.7f);
                fx(Particle.TOTEM_OF_UNDYING, main[win].getLocation().add(0, 1, 0), 40, 0.3);
            }
            if (t == jump + 22) reveal();
        }

        // ── 2: ВИХОР ЧАСУ ──
        void startFly(int i) {
            out[i] = true;
            if (main[i] == null) return;
            Vector dir = main[i].getLocation().toVector().subtract(base.toVector());
            dir.setY(0);
            if (dir.lengthSquared() < 1e-4) dir = new Vector(1, 0, 0);
            dir.normalize();
            fv[i] = dir.multiply(0.6).setY(0.3);
            fEnd[i] = t + 12;
            if (lab[i] != null) { lab[i].remove(); lab[i] = null; }
            snd(Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.5f, 1f);
        }

        void vortex() {
            Location S = base.clone().add(0, 1.4, 0);
            for (int i = 0; i < 8; i++) {
                if (t == 6 + i * 4) {
                    ItemDisplay idp = item(S, new ItemStack(slot[i].icon), 0.9f, Display.Billboard.CENTER);
                    main[i] = idp;
                    glow(i, true);
                    lab[i] = text(S.clone().add(0, 0.9, 0), labelComp(slot[i]), 0.8f);
                    fx(Particle.POOF, S, 10, 0.05);
                    snd(Sound.BLOCK_NOTE_BLOCK_PLING, 1.5f, 0.7f + i * 0.1f);
                }
            }
            int es = 125, cs = es + 6 * 9 + 25;
            double speed = 0.04 + 0.38 * Math.pow(Math.min(1.0, t / 130.0), 2);
            if (t >= cs) speed = 0.45;
            theta += speed;
            double rr = R * Math.min(1.0, Math.max(0.0, (t - 6) / 36.0));
            for (int k = 0; k < 7; k++) if (t == es + k * 9) startFly(elim.get(k));
            double cf = t >= cs ? Math.min(1.0, (t - cs) / 30.0) : 0;
            for (int i = 0; i < 8; i++) {
                if (main[i] == null) continue;
                if (fEnd[i] > 0) {
                    main[i].teleport(main[i].getLocation().add(fv[i]));
                    fx(Particle.CLOUD, main[i].getLocation(), 2, 0.01);
                    if (t >= fEnd[i]) {
                        fx(Particle.POOF, main[i].getLocation(), 15, 0.1);
                        rm(i);
                        fEnd[i] = 0;
                    }
                    continue;
                }
                if (out[i]) continue;
                double a = i * Math.PI * 2 / 8 + theta;
                double r = (i == win) ? rr * (1 - cf) : rr;
                double y = 1.6 + 0.3 * Math.sin(t * 0.12 + i) + (i == win ? 1.2 * cf : 0);
                Location l = base.clone().add(r * Math.cos(a), y, r * Math.sin(a));
                main[i].teleport(l);
                tf((Display) main[i], (float) (0.9 + (i == win ? 1.7 * cf : 0)), t * 0.12 + i);
                if (lab[i] != null) lab[i].teleport(l.clone().add(0, 0.9 + (i == win ? 1.1 * cf : 0), 0));
            }
            if (t >= cs && t < cs + 30 && t % 2 == 0) fx(Particle.END_ROD, base.clone().add(0, 2.6, 0), 6, 0.05);
            if (t == cs + 30) reveal();
        }

        // ── 3: РУЛЕТКА ──
        void highlight(int c) {
            for (int i = 0; i < 8; i++) {
                if (main[i] == null) continue;
                glow(i, i == c);
                tf((Display) main[i], i == c ? 1.8f : 1.0f, 0);
            }
        }

        void roulette() {
            for (int i = 0; i < 8; i++) {
                if (t == 6 + i * 4) {
                    Location l = pos(i, 1.5);
                    main[i] = item(l, new ItemStack(slot[i].icon), 1.0f, Display.Billboard.CENTER);
                    lab[i] = text(pos(i, 2.6), labelComp(slot[i]), 0.9f);
                    fx(Particle.POOF, l, 12, 0.05);
                    snd(Sound.BLOCK_NOTE_BLOCK_PLING, 1.5f, 0.6f + i * 0.12f);
                }
            }
            int start = 6 + 7 * 4 + 25;
            if (t == start) {
                cursor = rnd.nextInt(8);
                steps = 24 + ((win - cursor + 8) % 8);
                kStep = 0;
                nextAt = t;
            }
            if (nextAt >= 0 && doneT < 0 && t == nextAt) {
                highlight(cursor);
                snd(Sound.BLOCK_NOTE_BLOCK_PLING, 1.5f, 0.6f + 1.4f * kStep / steps);
                if (kStep == steps) {
                    doneT = t;
                    snd(Sound.ENTITY_PLAYER_LEVELUP, 2f, 1f);
                    fx(Particle.TOTEM_OF_UNDYING, pos(win, 1.8), 30, 0.2);
                } else {
                    kStep++;
                    cursor = (cursor + 1) % 8;
                    nextAt = t + 2 + (kStep * kStep) / (steps * 3);
                }
            }
            if (doneT >= 0) {
                if (t == doneT + 25) {
                    for (int i = 0; i < 8; i++) if (i != win) { out[i] = true; fx(Particle.POOF, pos(i, 1.5), 15, 0.1); rm(i); }
                    snd(Sound.ENTITY_ENDERMAN_TELEPORT, 2f, 1.3f);
                }
                if (t > doneT + 25 && t <= doneT + 55 && main[win] != null) {
                    double f = (t - doneT - 25) / 30.0;
                    Location from = pos(win, 1.5), to = base.clone().add(0, 2.7, 0);
                    Location l = from.clone().add((to.getX() - from.getX()) * f, (to.getY() - from.getY()) * f, (to.getZ() - from.getZ()) * f);
                    main[win].teleport(l);
                    tf((Display) main[win], (float) (1.8 + 1.4 * f), t * 0.15);
                    if (lab[win] != null) lab[win].teleport(l.clone().add(0, 1.4, 0));
                    fx(Particle.END_ROD, l, 4, 0.02);
                }
                if (t == doneT + 55) reveal();
            }
        }

        // ── фінал ──
        void reveal() {
            if (revealed) return;
            revealed = true;
            revT = t;
            fwLeft = 2 + prize.rarity.ordinal();
            Component big = legacy(prize.name).append(Component.newline())
                    .append(Component.text("Шанс: " + fmt(prize.pct) + "%", prize.rarity.tc(), TextDecoration.BOLD));
            text(base.clone().add(0, 4.8, 0), big, 3.4f);
            firework(base.clone().add(0, 2.5, 0), prize.rarity.bc());
            snd(Sound.UI_TOAST_CHALLENGE_COMPLETE, 2f, 1f);
            snd(Sound.ENTITY_PLAYER_LEVELUP, 2f, 1.2f);
            p.showTitle(Title.title(legacy(prize.name),
                    Component.text("Шанс: " + fmt(prize.pct) + "%", prize.rarity.tc(), TextDecoration.BOLD),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(3000), Duration.ofMillis(600))));
            giveReward();
            endAt = t + 90;
        }

        void giveReward() {
            if (rewardGiven) return;
            rewardGiven = true;
            for (String c : prize.commands)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()));
            p.sendMessage(legacy("&6&l✦ InterTime &8» &fТи отримав: ").append(legacy(prize.name))
                    .append(Component.text(" (шанс " + fmt(prize.pct) + "%)", prize.rarity.tc())));
            if (prize.rarity.ordinal() >= Rarity.EPIC.ordinal()) {
                Bukkit.broadcast(legacy("&6&l✦ InterTime &8» &e" + p.getName() + " &fвідкрив ").append(legacy(d.name))
                        .append(legacy(" &fі виграв ")).append(legacy(prize.name))
                        .append(Component.text(" (шанс " + fmt(prize.pct) + "%)", prize.rarity.tc(), TextDecoration.BOLD)));
            }
        }

        void forceFinish(boolean give) {
            if (give) giveReward();
            finish();
        }

        void finish() {
            if (finished) return;
            finished = true;
            try { cancel(); } catch (IllegalStateException ignored) { }
            for (Entity e : all) if (e.isValid()) e.remove();
            shows.remove(p.getUniqueId());
            busy.remove(bkey);
        }
    }
}
